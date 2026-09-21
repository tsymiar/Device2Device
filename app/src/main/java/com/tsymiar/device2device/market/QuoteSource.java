package com.tsymiar.device2device.market;

import android.net.Uri;
import android.util.Log;

import com.tsymiar.device2device.utils.HttpsRequest;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 行情取数：对标 MyAutomatic/toolset/matkline.py 的数据源层（SOURCES / normalize_symbol /
 * candidate_sources / SOURCE_LOADERS / resolve_points）。
 *
 * 数据源按「代码形态」分类，与脚本一一对应：
 *  - tencent    A股/指数/基金   日线(原生支持区间) + 1/5/15/30/60分钟
 *  - eastmoney  东方财富        A股/期货/港股
 *  - gold       沪金主连 AU0    日线全量历史 + 分钟线，均为真实OHLC
 *  - xau        伦敦金现货      日线真实OHLC；分钟线由当日分时线+实时快照聚合
 *  - gc         纽约金 COMEX    同上
 *  - crude      纽约原油 WTI CL 同上（新浪 GlobalFuturesService）
 *  - brent      布伦特原油 OIL  同上
 *  - ng         美国天然气 NG   同上
 *  - usd        美元指数 UDI    分钟线:东财分时聚合(回退新浪外汇快照)；日线:欧洲央行汇率反算
 *  - binance    加密货币       （data-api.binance.vision 直连，无需密钥）
 *  - auto       按标的形态挑源并逐个容错回退（对应脚本 resolve_points）
 *
 * 均为免密钥公开 HTTPS 接口；回调在**主线程**执行（底层 HttpsRequest 已切回 UI 线程）。
 */
public final class QuoteSource {

    private static final String TAG = QuoteSource.class.getSimpleName();

    public static final String AUTO = "auto";
    public static final String TENCENT = "tencent";
    public static final String EAST = "eastmoney";
    public static final String GOLD = "gold";
    public static final String XAU = "xau";
    public static final String GC = "gc";
    public static final String CRUDE = "crude";
    public static final String BRENT = "brent";
    public static final String NG = "ng";
    public static final String USD = "usd";
    public static final String BINANCE = "binance";

    /** 可选数据源（UI 下拉用，顺序即展示顺序） */
    public static final String[] SOURCES = {AUTO, TENCENT, EAST, GOLD, XAU, GC, CRUDE, BRENT, NG,
            USD, BINANCE};

    /** auto 模式的尝试顺序（okx 接口在国内不可达，已移除） */
    private static final List<String> AUTO_ORDER = Arrays.asList(TENCENT, EAST, BINANCE);

    /** 新浪国际期货：源名 -> {K线代码, 快照代码}（脚本 SINA_FUTURES） */
    private static final Map<String, String[]> SINA_FUTURES = new LinkedHashMap<>();
    /** 支持别名/中文名选源的数据源集合（脚本 COMMODITY_SOURCES） */
    private static final java.util.Set<String> COMMODITY_SOURCES = new java.util.HashSet<>();
    /** 各数据源单次请求能返回的最大K线根数（脚本 SINGLE_REQUEST_MAX） */
    private static final Map<String, Integer> SINGLE_REQUEST_MAX = new HashMap<>();

    static {
        SINA_FUTURES.put(XAU, new String[]{"XAU", "hf_XAU"});
        SINA_FUTURES.put(GC, new String[]{"GC", "hf_GC"});
        SINA_FUTURES.put(CRUDE, new String[]{"CL", "hf_CL"});
        SINA_FUTURES.put(BRENT, new String[]{"OIL", "hf_OIL"});
        SINA_FUTURES.put(NG, new String[]{"NG", "hf_NG"});
        COMMODITY_SOURCES.addAll(SINA_FUTURES.keySet());
        COMMODITY_SOURCES.add(GOLD);
        COMMODITY_SOURCES.add(USD);
        SINGLE_REQUEST_MAX.put(TENCENT, 640);
        SINGLE_REQUEST_MAX.put(EAST, 10000);
        SINGLE_REQUEST_MAX.put(BINANCE, 1000);
    }

    // ------------------------------------------------------------------
    // 各数据源地址（脚本 SOURCES 配置）
    // ------------------------------------------------------------------
    private static final String TENCENT_KLINE = "https://ifzq.gtimg.cn/appstock/app/fqkline/get";
    private static final String TENCENT_KLINE_MIRROR = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get";
    private static final String TENCENT_MINUTE = "https://ifzq.gtimg.cn/appstock/app/kline/mkline";
    private static final String TENCENT_MINUTE_MIRROR = "https://web.ifzq.gtimg.cn/appstock/app/kline/mkline";
    private static final String EAST_KLINE = "https://push2his.eastmoney.com/api/qt/stock/kline/get";
    private static final String BINANCE_KLINE = "https://data-api.binance.vision/api/v3/klines";
    private static final String BINANCE_KLINE_MIRROR = "https://api.binance.com/api/v3/klines";
    /** 腾讯智能搜索：名称/拼音/代码 -> 标的（响应为 \\uXXXX 纯ASCII转义，不受编码影响） */
    private static final String TENCENT_SEARCH = "https://smartbox.gtimg.cn/s3/";
    /** 东财联想词：搜索备用（UTF-8 JSON） */
    private static final String EAST_SEARCH = "https://searchapi.eastmoney.com/api/suggest/get";
    /** 新浪国内期货（沪金/沪银）：var%20_ 用拼接而非格式化，避免 % 被当作格式符 */
    private static final String SINA_INNER = "https://stock2.finance.sina.com.cn/futures/api/jsonp.php/var%20_";
    /** 新浪国际期货（伦敦金/纽约金） */
    private static final String SINA_GLOBAL = "https://stock2.finance.sina.com.cn/futures/api/jsonp.php/var%20_";
    private static final String SINA_SNAPSHOT = "https://hq.sinajs.cn/list=";
    /** 东财分时(1分钟)：美元指数等没有免费历史日线的品种，用它聚合出分钟K线 */
    private static final String EAST_TRENDS = "https://push2his.eastmoney.com/api/qt/stock/trends2/get";
    /** 美元指数在东财的 secid（K线接口对 100.UDI 无数据） */
    private static final String USD_TRENDS_SECID = "100.UDI";
    /** 美元指数在新浪外汇的快照代码（东财不可达时的实时价回退，只有当前一笔） */
    private static final String USD_SINA_CODE = "DINIW";
    /** 欧洲央行参考汇率（免密钥）：美元指数没有免费日线源，按 ICE 官方权重反算 */
    private static final String FRANKFURTER = "https://api.frankfurter.dev/v1/";
    private static final String USDX_CURRENCIES = "EUR,JPY,GBP,CAD,SEK,CHF";
    private static final double USDX_BASE = 50.14348112;

    // ------------------------------------------------------------------
    // 周期映射与各源支持的周期
    // ------------------------------------------------------------------
    private static final Map<String, String> INTERVAL_ALIAS = new HashMap<>();
    private static final Map<String, Integer> MINUTE_STEP = new HashMap<>();
    private static final Map<String, String> TENCENT_DAILY_KEY = new HashMap<>();
    private static final Map<String, String> TENCENT_MINUTE_KEY = new HashMap<>();
    private static final Map<String, Integer> EAST_KLT = new HashMap<>();
    private static final Map<String, String> BINANCE_BAR = new HashMap<>();
    private static final Map<String, String[]> SUPPORTED = new HashMap<>();

    static {
        INTERVAL_ALIAS.put("day", "1d");
        INTERVAL_ALIAS.put("d", "1d");
        INTERVAL_ALIAS.put("1day", "1d");
        INTERVAL_ALIAS.put("week", "1w");
        INTERVAL_ALIAS.put("w", "1w");
        INTERVAL_ALIAS.put("month", "1M");
        INTERVAL_ALIAS.put("min", "1m");
        INTERVAL_ALIAS.put("1min", "1m");
        INTERVAL_ALIAS.put("hour", "1h");
        INTERVAL_ALIAS.put("1hour", "1h");
        INTERVAL_ALIAS.put("quarter", "1Q");
        INTERVAL_ALIAS.put("season", "1Q");
        INTERVAL_ALIAS.put("q", "1Q");
        INTERVAL_ALIAS.put("year", "1Y");
        INTERVAL_ALIAS.put("y", "1Y");

        MINUTE_STEP.put("1m", 1);
        MINUTE_STEP.put("5m", 5);
        MINUTE_STEP.put("15m", 15);
        MINUTE_STEP.put("30m", 30);
        MINUTE_STEP.put("60m", 60);
        MINUTE_STEP.put("1h", 60);

        TENCENT_DAILY_KEY.put("1d", "day");
        TENCENT_DAILY_KEY.put("1w", "week");
        TENCENT_DAILY_KEY.put("1M", "month");
        TENCENT_MINUTE_KEY.put("1m", "m1");
        TENCENT_MINUTE_KEY.put("5m", "m5");
        TENCENT_MINUTE_KEY.put("15m", "m15");
        TENCENT_MINUTE_KEY.put("30m", "m30");
        TENCENT_MINUTE_KEY.put("60m", "m60");
        TENCENT_MINUTE_KEY.put("1h", "m60");

        EAST_KLT.put("1m", 1);
        EAST_KLT.put("5m", 5);
        EAST_KLT.put("15m", 15);
        EAST_KLT.put("30m", 30);
        EAST_KLT.put("60m", 60);
        EAST_KLT.put("1h", 60);
        EAST_KLT.put("1d", 101);
        EAST_KLT.put("1w", 102);
        EAST_KLT.put("1M", 103);

        BINANCE_BAR.put("60m", "1h");

        // 1h 与 60m 同为 60 分钟，统一用 60m 表示「小时线」，避免下拉里出现重复项
        // 1Q=季线，1Y=年线，两者没有现成接口，由月线（或日线）聚合出来
        String[] cn = {"1m", "5m", "30m", "60m", "1d", "1w", "1M", "1Q", "1Y"};
        // 新浪期货（国内/国际）只到日线；美元指数仅有东财分时，无免费日线
        String[] futures = {"1m", "5m", "30m", "60m", "1d"};
        // 美元指数：分钟线靠分时/快照聚合，日线及以上由汇率反算（已补齐日线支持）
        String[] usdIndex = {"1m", "5m", "30m", "60m", "1d", "1w", "1M", "1Q", "1Y"};
        SUPPORTED.put(TENCENT, cn);
        SUPPORTED.put(EAST, cn);
        SUPPORTED.put(BINANCE, new String[]{"1m", "3m", "5m", "30m", "2h", "4h",
                "6h", "8h", "12h", "1d", "3d", "1w", "1M", "1Q", "1Y"});

        SUPPORTED.put(GOLD, futures);
        SUPPORTED.put(XAU, futures);
        SUPPORTED.put(GC, futures);
        SUPPORTED.put(CRUDE, futures);
        SUPPORTED.put(BRENT, futures);
        SUPPORTED.put(NG, futures);
        SUPPORTED.put(USD, usdIndex);
        // auto 用国内源的周期集合（覆盖 A股/加密/黄金的公共子集）
        SUPPORTED.put(AUTO, cn);
    }

    // ------------------------------------------------------------------
    // 标的形态识别（auto 模式据此挑源）
    // ------------------------------------------------------------------
    private static final Pattern CRYPTO = Pattern.compile("^[A-Za-z0-9]{2,12}[-/]?(?:USDT|USDC|USD|BTC|ETH)$");
    private static final Pattern DOMESTIC = Pattern.compile("^(?:sh|sz|bj|hk|us|nf_|hf_|[0-9]{6})",
            Pattern.CASE_INSENSITIVE);
    /** 商品/指数别名：输入框可直接写中文或常用写法（脚本 COMMODITY_ALIASES） */
    private static final Map<String, String> COMMODITY_ALIASES = new HashMap<>();
    /** 别名指向的标的 -> 优先数据源（脚本 SYMBOL_SOURCE_ORDER） */
    private static final Map<String, List<String>> SYMBOL_SOURCE_ORDER = new HashMap<>();
    /** 前缀匹配：别名表没收录的写法（如 au2412、原油2411）也能落到合适的数据源 */
    private static final List<Pattern> PREFIX_PATTERNS = new ArrayList<>();
    private static final List<List<String>> PREFIX_SOURCES = new ArrayList<>();

    static {
        // 贵金属
        COMMODITY_ALIASES.put("au", "AU0");
        COMMODITY_ALIASES.put("au0", "AU0");
        COMMODITY_ALIASES.put("沪金", "AU0");
        COMMODITY_ALIASES.put("黄金", "AU0");
        COMMODITY_ALIASES.put("黄金期货", "AU0");
        COMMODITY_ALIASES.put("gold", "AU0");
        COMMODITY_ALIASES.put("xau", "XAU");
        COMMODITY_ALIASES.put("xauusd", "XAU");
        COMMODITY_ALIASES.put("伦敦金", "XAU");
        COMMODITY_ALIASES.put("现货黄金", "XAU");
        COMMODITY_ALIASES.put("国际黄金", "XAU");
        COMMODITY_ALIASES.put("gc", "GC");
        COMMODITY_ALIASES.put("gc0", "GC");
        COMMODITY_ALIASES.put("comex", "GC");
        COMMODITY_ALIASES.put("纽约金", "GC");
        COMMODITY_ALIASES.put("纽约黄金", "GC");
        COMMODITY_ALIASES.put("ag", "AG0");
        COMMODITY_ALIASES.put("ag0", "AG0");
        COMMODITY_ALIASES.put("沪银", "AG0");
        COMMODITY_ALIASES.put("白银", "AG0");
        COMMODITY_ALIASES.put("现货白银", "AG0");
        // 能源
        COMMODITY_ALIASES.put("cl", "CL");
        COMMODITY_ALIASES.put("cl0", "CL");
        COMMODITY_ALIASES.put("wti", "CL");
        COMMODITY_ALIASES.put("crude", "CL");
        COMMODITY_ALIASES.put("原油", "CL");
        COMMODITY_ALIASES.put("美原油", "CL");
        COMMODITY_ALIASES.put("纽约原油", "CL");
        COMMODITY_ALIASES.put("美国原油", "CL");
        COMMODITY_ALIASES.put("oil", "OIL");
        COMMODITY_ALIASES.put("brent", "OIL");
        COMMODITY_ALIASES.put("布伦特", "OIL");
        COMMODITY_ALIASES.put("布伦特原油", "OIL");
        COMMODITY_ALIASES.put("ng", "NG");
        COMMODITY_ALIASES.put("ng0", "NG");
        COMMODITY_ALIASES.put("天然气", "NG");
        COMMODITY_ALIASES.put("美国天然气", "NG");
        // 指数
        COMMODITY_ALIASES.put("udi", "UDI");
        COMMODITY_ALIASES.put("dxy", "UDI");
        COMMODITY_ALIASES.put("usdx", "UDI");
        COMMODITY_ALIASES.put("usd", "UDI");
        COMMODITY_ALIASES.put("美元", "UDI");
        COMMODITY_ALIASES.put("美元指数", "UDI");

        SYMBOL_SOURCE_ORDER.put("AU0", Arrays.asList(GOLD, XAU, GC));
        SYMBOL_SOURCE_ORDER.put("AG0", Collections.singletonList(GOLD));
        SYMBOL_SOURCE_ORDER.put("XAU", Arrays.asList(XAU, GC));
        SYMBOL_SOURCE_ORDER.put("GC", Arrays.asList(GC, XAU));
        SYMBOL_SOURCE_ORDER.put("CL", Collections.singletonList(CRUDE));
        SYMBOL_SOURCE_ORDER.put("OIL", Collections.singletonList(BRENT));
        SYMBOL_SOURCE_ORDER.put("NG", Collections.singletonList(NG));
        SYMBOL_SOURCE_ORDER.put("UDI", Collections.singletonList(USD));

        prefix("^(?:xau|伦敦金|现货黄金|国际黄金)", XAU, GC);
        prefix("^(?:gc0?|comex|纽约金)", GC, XAU);
        prefix("^(?:au0?|ag0?|沪金|沪银|黄金|白银)", GOLD, XAU, GC);
        prefix("^(?:cl0?|wti|crude|纽约原油|美国原油|原油)", CRUDE);
        prefix("^(?:oil|brent|布伦特)", BRENT);
        prefix("^(?:ng0?|天然气)", NG);
        prefix("^(?:udi|dxy|usdx|美元)", USD);
    }

    private static void prefix(String regex, String... sources) {
        PREFIX_PATTERNS.add(Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
        PREFIX_SOURCES.add(Arrays.asList(sources));
    }

    private static final String FULL_FMT = "yyyy-MM-dd HH:mm:ss";
    private static final SimpleDateFormat TENCENT_MINUTE_FMT = new SimpleDateFormat("yyyyMMddHHmm", Locale.US);
    private static final Pattern JSONP_BODY = Pattern.compile("\\((.*)\\)\\s*;?\\s*$", Pattern.DOTALL);
    private static final Pattern SNAPSHOT_BODY = Pattern.compile("=\"([^\"]*)\"");

    public interface Callback {
        void onLoaded(String source, String code, List<Quote> quotes);

        void onFailed(String message);
    }

    private interface StepCallback {
        void onResult(List<Quote> quotes);

        void onError(String message);
    }

    private interface Parser {
        List<Quote> parse(String body) throws Exception;
    }

    private interface TextConsumer {
        void accept(String body) throws Exception;
    }

    private QuoteSource() {
    }

    // ------------------------------------------------------------------
    // 对外：UI 用的元信息
    // ------------------------------------------------------------------

    /** 下拉标题：源名 + 覆盖范围 */
    public static String label(String source) {
        switch (source == null ? AUTO : source) {
            case TENCENT:
                return "tencent   A股/指数/基金";
            case EAST:
                return "eastmoney 东方财富";
            case GOLD:
                return "gold      沪金主连 AU0";
            case XAU:
                return "xau       伦敦金现货";
            case GC:
                return "gc        纽约金 COMEX";
            case CRUDE:
                return "crude     纽约原油 WTI";
            case BRENT:
                return "brent     布伦特原油";
            case NG:
                return "ng        美国天然气";
            case USD:
                return "usd       美元指数";
            case BINANCE:
                return "binance   加密货币";
            default:
                return "auto      自动识别";
        }
    }

    /** 代码输入框提示：按源分类给出该源的代码写法 */
    public static String symbolHint(String source) {
        switch (source == null ? AUTO : source) {
            case TENCENT:
                return "A股名称或代码：贵州茅台 / 茅台 / gzmt / sh600519";
            case EAST:
                return "A股/期货/港股：1.600519 / 600519 / 0.000001";
            case GOLD:
                return "沪金沪银：AU0 / 沪金 / 黄金 / AG0 / 白银";
            case XAU:
                return "伦敦金：XAU / 伦敦金 / 现货黄金";
            case GC:
                return "纽约金：GC / 纽约金 / COMEX";
            case CRUDE:
                return "WTI原油：CL / 原油 / 美原油 / WTI";
            case BRENT:
                return "布伦特原油：OIL / 布伦特 / BRENT";
            case NG:
                return "天然气：NG / 天然气";
            case USD:
                return "美元指数：UDI / DXY / 美元";
            case BINANCE:
                return "加密货币：BTCUSDT / ETHUSDT";
            default:
                return "自动识别：茅台 / 600519 / BTCUSDT / 沪金 / 伦敦金";
        }
    }

    public static String defaultSymbol(String source) {
        switch (source == null ? AUTO : source) {
            case TENCENT:
                return "sh600519";
            case EAST:
                return "1.600519";
            case GOLD:
                return "AU0";
            case XAU:
                return "XAU";
            case GC:
                return "GC";
            case CRUDE:
                return "CL";
            case BRENT:
                return "OIL";
            case NG:
                return "NG";
            case USD:
                return "UDI";
            case BINANCE:
                return "BTCUSDT";
            default:
                return "sh600519";
        }
    }

    /** 输入框里已填的标的形态是否属于该数据源（切换数据源时用于决定要不要换成默认代码） */
    public static boolean accepts(String source, String symbol) {
        String text = symbol == null ? "" : symbol.trim();
        if (text.isEmpty()) return true;
        if (COMMODITY_SOURCES.contains(source)) {
            String target = COMMODITY_ALIASES.get(text.toLowerCase(Locale.US));
            List<String> order = target == null ? null : SYMBOL_SOURCE_ORDER.get(target);
            if (order != null) return order.contains(source);
            for (int i = 0; i < PREFIX_PATTERNS.size(); i++) {
                if (PREFIX_PATTERNS.get(i).matcher(text).find()) {
                    return PREFIX_SOURCES.get(i).contains(source);
                }
            }
            return true;
        }
        if (BINANCE.equals(source)) {
            return CRYPTO.matcher(text.toUpperCase(Locale.US)).matches();
        }
        return DOMESTIC.matcher(text).find();
    }

    /** 该源支持的周期（UI 根据所选源重建下拉） */
    public static String[] intervals(String source) {
        String[] list = SUPPORTED.get(source == null ? AUTO : source);
        return list == null ? SUPPORTED.get(AUTO) : list;
    }

    /** 下拉里给人看的周期名：季/年/月/周/日给中文，分钟线与小时线保持 5m / 1h 这类写法 */
    public static String intervalLabel(String interval) {
        if (interval == null) return "";
        switch (interval) {
            case "1Q":
                return "季K";
            case "60m":
            case "1h":
                return "1h";
            case "1Y":
                return "年K";
            case "1M":
                return "月K";
            case "1w":
                return "周K";
            case "1d":
                return "日K";
            default:
                return interval;
        }
    }

    // ------------------------------------------------------------------
    // 对外：取数
    // ------------------------------------------------------------------

    public static void load(String source, String symbol, String interval, int limit, Callback callback) {
        List<String> names = candidates(source, symbol);
        step(new ArrayDeque<>(names), symbol, interval, limit, callback, new ArrayList<String>());
    }

    // ------------------------------------------------------------------
    // 对外：标的搜索（名称 / 拼音 / 代码 -> 代码 + 中文名）
    // ------------------------------------------------------------------

    /** 搜索命中的标的：code 可直接喂给 load()，name 用于主图标题 */
    public static final class Symbol {
        public final String code;
        public final String name;

        Symbol(String code, String name) {
            this.code = code;
            this.name = name;
        }

        @Override
        public String toString() {
            return name + "  " + code;
        }
    }

    public interface SearchCallback {
        void onResult(List<Symbol> items);

        void onError(String message);
    }

    /**
     * 按名称/拼音/代码查标的：腾讯智能框（v_hint，\\uXXXX 纯ASCII转义，不受GBK影响）为主，
     * 东财联想词兜底。回调在主线程。同时也用于「代码 -> 名称」反查（主图标题用）。
     */
    public static void searchSymbol(String keyword, SearchCallback cb) {
        final String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty()) {
            cb.onError("请输入名称或代码");
            return;
        }
        String url = TENCENT_SEARCH + "?q=" + Uri.encode(kw) + "&t=all";
        HttpsRequest.executeRequest(url, "GET", headersFor(TENCENT), null,
                new HttpsRequest.HttpsRequestCallback() {
                    @Override
                    public void onSuccess(int responseCode, String response,
                                          Map<String, String> respHeaders) {
                        List<Symbol> items = parseSmartbox(response);
                        if (!items.isEmpty()) {
                            cb.onResult(items);
                            return;
                        }
                        searchEastSuggest(kw, cb);
                    }

                    @Override
                    public void onFailure(Exception e) {
                        searchEastSuggest(kw, cb);
                    }
                });
    }

    /** 东财联想词（搜索备用，UTF-8 JSON） */
    private static void searchEastSuggest(String kw, SearchCallback cb) {
        String url = EAST_SEARCH + "?input=" + Uri.encode(kw)
                + "&type=14&token=D43BF722C8E33BDC906FB84D85E326E8&count=10";
        HttpsRequest.executeRequest(url, "GET", headersFor(EAST), null,
                new HttpsRequest.HttpsRequestCallback() {
                    @Override
                    public void onSuccess(int responseCode, String response,
                                          Map<String, String> respHeaders) {
                        try {
                            List<Symbol> items = parseEastSuggest(response);
                            if (items.isEmpty()) {
                                cb.onError("未找到匹配的标的");
                                return;
                            }
                            cb.onResult(items);
                        } catch (Exception e) {
                            cb.onError("搜索结果解析失败");
                        }
                    }

                    @Override
                    public void onFailure(Exception e) {
                        cb.onError("搜索失败：" + e.getMessage());
                    }
                });
    }

    /** 腾讯智能框：v_hint="市场~代码~名称~拼音~类型^…"，多条以 ^ 分隔 */
    private static List<Symbol> parseSmartbox(String body) {
        List<Symbol> items = new ArrayList<>();
        if (body == null) return items;
        Matcher matcher = Pattern.compile("v_hint=\"([^\"]*)\"").matcher(body);
        if (!matcher.find()) return items;
        String inner = unescapeUnicode(matcher.group(1));
        if (inner.isEmpty() || "N".equals(inner)) return items;
        for (String entry : inner.split("\\^")) {
            String[] parts = entry.split("~");
            if (parts.length < 3) continue;
            String market = parts[0].trim().toLowerCase(Locale.US);
            String code = parts[1].trim();
            String name = parts[2].trim();
            if (code.isEmpty() || name.isEmpty()) continue;
            if (market.startsWith("sh") || market.startsWith("sz") || market.startsWith("bj")
                    || market.startsWith("hk") || market.startsWith("us")) {
                items.add(new Symbol(market + code, name));
            }
        }
        return items;
    }

    /** 东财联想词：QuotationCodeTable.Data[] 内 QuoteID 形如 1.600519 */
    private static List<Symbol> parseEastSuggest(String body) throws Exception {
        List<Symbol> items = new ArrayList<>();
        JSONObject root = new JSONObject(body);
        JSONObject table = root.optJSONObject("QuotationCodeTable");
        JSONArray rows = table == null ? null : table.optJSONArray("Data");
        if (rows == null) return items;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            String name = row.optString("Name", "").trim();
            String quoteId = row.optString("QuoteID", "").trim();
            if (name.isEmpty() || quoteId.isEmpty()) continue;
            String code = quoteId;
            if (quoteId.startsWith("1.")) code = "sh" + quoteId.substring(2);
            else if (quoteId.startsWith("0.")) code = "sz" + quoteId.substring(2);
            else if (quoteId.contains(".")) code = quoteId;
            items.add(new Symbol(code, name));
        }
        return items;
    }

    /** 把 "贵\u5dde" 这类转义还原成中文（腾讯搜索接口返回的是 ASCII 转义） */
    private static String unescapeUnicode(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 5 < text.length() && text.charAt(i + 1) == 'u') {
                try {
                    sb.append((char) Integer.parseInt(text.substring(i + 2, i + 6), 16));
                    i += 5;
                    continue;
                } catch (Exception ignored) {
                    // 不是合法转义，按原字符处理
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** 商品/指数代码 -> 中文名（主图标题用，股票名称由搜索接口返回） */
    public static String aliasName(String code) {
        String upper = (code == null ? "" : code.trim().toUpperCase(Locale.US));
        switch (upper) {
            case "AU0":
                return "沪金主连";
            case "AG0":
                return "沪银主连";
            case "XAU":
                return "伦敦金现货";
            case "GC":
                return "纽约金 COMEX";
            case "CL":
                return "纽约原油 WTI";
            case "OIL":
                return "布伦特原油";
            case "NG":
                return "美国天然气";
            case "UDI":
                return "美元指数";
            default:
                return "";
        }
    }

    /** 输入的文本是否需要走「名称搜索」（含中文、或拼音/英文且不是已知代码写法） */
    public static boolean needsSearch(String text) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) return false;
        if (COMMODITY_ALIASES.containsKey(value.toLowerCase(Locale.US))) return false;
        for (Pattern pattern : PREFIX_PATTERNS) {
            if (pattern.matcher(value).find()) return false;
        }
        if (DOMESTIC.matcher(value).find()) return false;
        if (CRYPTO.matcher(value.toUpperCase(Locale.US)).matches()) return false;
        return true;
    }

    /** auto 模式按标的形态挑源（脚本 candidate_sources） */
    private static List<String> candidates(String source, String symbol) {
        if (source != null && !AUTO.equals(source)) {
            if (Arrays.asList(SOURCES).contains(source)) return Collections.singletonList(source);
            return Collections.singletonList(AUTO);
        }
        String text = symbol == null ? "" : symbol.trim();
        if (!text.isEmpty()) {
            String target = COMMODITY_ALIASES.get(text.toLowerCase(Locale.US));
            if (target != null) {
                List<String> order = SYMBOL_SOURCE_ORDER.get(target);
                return order != null ? order : Arrays.asList(GOLD, XAU, GC);
            }
            for (int i = 0; i < PREFIX_PATTERNS.size(); i++) {
                if (PREFIX_PATTERNS.get(i).matcher(text).find()) return PREFIX_SOURCES.get(i);
            }
            if (CRYPTO.matcher(text.toUpperCase(Locale.US)).matches()) return Collections.singletonList(BINANCE);
            if (DOMESTIC.matcher(text).find()) return Arrays.asList(TENCENT, EAST);
        }
        return AUTO_ORDER;
    }

    private static void step(Queue<String> pending, String symbol, String interval, int limit,
                             Callback callback, List<String> errors) {
        if (pending.isEmpty()) {
            callback.onFailed(errors.isEmpty() ? "未知错误" : join(errors));
            return;
        }
        String name = pending.poll();
        String norm = normalizeInterval(interval);
        if (!supports(name, norm)) {
            // 与脚本一致：周期不支持属于参数问题，auto 也不靠回退掩盖
            callback.onFailed(name + " 不支持周期 " + norm
                    + "，可用周期: " + join(Arrays.asList(intervals(name))));
            return;
        }
        final String code = normalizeSymbol(name, symbol);
        loadOne(name, code, norm, limit, new StepCallback() {
            @Override
            public void onResult(List<Quote> quotes) {
                List<Quote> tail = tail(dropFuturePoints(quotes), limit);
                if (tail.isEmpty()) {
                    errors.add(name + ": 无K线数据");
                    step(pending, symbol, interval, limit, callback, errors);
                    return;
                }
                callback.onLoaded(name, code, tail);
            }

            @Override
            public void onError(String message) {
                errors.add(name + ": " + message);
                step(pending, symbol, interval, limit, callback, errors);
            }
        });
    }

    private static boolean supports(String source, String interval) {
        String[] list = intervals(source);
        return Arrays.asList(list).contains(interval);
    }

    /** 分派到各数据源加载器（脚本 SOURCE_LOADERS） */
    private static void loadOne(String name, String code, String interval, int limit, StepCallback cb) {
        try {
            if (SINA_FUTURES.containsKey(name)) {
                loadSinaFutures(name, code, interval, cb);
                return;
            }
            if (GOLD.equals(name)) {
                loadSinaInner(code, interval, cb);
                return;
            }
            if (USD.equals(name)) {
                loadUsdIndex(interval, cb);
                return;
            }
            List<String> urls = buildUrls(name, code, interval, limit);
            // 季/年线：月线到手后按季度/年度合并成目标周期
            final boolean composed = isComposedInterval(interval);
            StepCallback sink = composed ? new StepCallback() {
                @Override
                public void onResult(List<Quote> quotes) {
                    cb.onResult(resample(quotes, interval));
                }

                @Override
                public void onError(String message) {
                    cb.onError(message);
                }
            } : cb;
            fetchAny(new ArrayDeque<>(urls), headersFor(name),
                    body -> parseKline(name, code, composed ? "1M" : interval, body), sink);
        } catch (Exception e) {
            cb.onError(String.valueOf(e.getMessage()));
        }
    }

    /** 沪金/沪银：日线 getDailyKLine；分钟线 getFewMinLine，均为真实OHLC */
    private static void loadSinaInner(String code, String interval, StepCallback cb) {
        // 新浪只有日线与分钟线：季/年线取日线回来自己合并
        final boolean composed = isComposedInterval(interval);
        boolean daily = "1d".equals(interval) || composed;
        String method = daily ? "getDailyKLine" : "getFewMinLine";
        String url = SINA_INNER + code + "/InnerFuturesNewService." + method + "?symbol=" + code;
        if (!daily) {
            Integer step = MINUTE_STEP.get(interval);
            url += "&type=" + (step == null ? 5 : step);
        }
        StepCallback sink = composed ? new StepCallback() {
            @Override
            public void onResult(List<Quote> quotes) {
                cb.onResult(resample(quotes, interval));
            }

            @Override
            public void onError(String message) {
                cb.onError(message);
            }
        } : cb;
        fetchAny(new ArrayDeque<>(Collections.singletonList(url)), sinaHeaders(),
                body -> parseSinaInner(new JSONArray(parseJsonp(body)), code), sink);
    }

    /** 新浪国际期货（黄金/原油/天然气）：日线真实OHLC；分钟线用当日分时线 + 实时快照聚合 */
    private static void loadSinaFutures(String name, String code, String interval, StepCallback cb) {
        String[] conf = SINA_FUTURES.get(name);
        if (conf == null) {
            cb.onError("未知数据源 " + name);
            return;
        }
        if ("1d".equals(interval)) {
            String url = SINA_GLOBAL + code
                    + "/GlobalFuturesService.getGlobalFuturesDailyKLine?symbol=" + code;
            fetchAny(new ArrayDeque<>(Collections.singletonList(url)), sinaHeaders(),
                    body -> parseSinaGlobal(new JSONArray(parseJsonp(body)), code), cb);
            return;
        }
        // 当日分时线：一次拿到全天每分钟价格，无需从启动开始慢慢累积
        String url = SINA_GLOBAL + code + "/GlobalFuturesService.getGlobalFuturesMinLine?symbol=" + code;
        Integer stepValue = MINUTE_STEP.get(interval);
        final int step = stepValue == null ? 5 : stepValue;
        fetchText(url, sinaHeaders(), body -> {
            List<Tick> ticks = parseSinaMinline(parseJsonp(body), code);
            // 再补一笔最新快照，让最后一根K线跟到最新价
            fetchText(SINA_SNAPSHOT + conf[1], sinaHeaders(), snapBody -> {
                Tick tick = parseSinaSnapshot(snapBody);
                if (tick != null) ticks.add(tick);
                if (ticks.isEmpty()) throw new IllegalArgumentException(conf[1] + " 暂无有效价格");
                cb.onResult(aggregateSnapshot(name, ticks, step, conf[1]));
            }, cb);
        }, cb);
    }

    /**
     * 美元指数：多路回退，任一路通就能出图。
     *  - 分钟线：东财分时(最多5个交易日) -> 新浪外汇实时快照（东财域名常不可达）
     *  - 日线及以上：欧洲央行参考汇率按 ICE 权重反算（ICE 官方日线无免费源）
     */
    private static void loadUsdIndex(String interval, StepCallback cb) {
        Integer stepValue = MINUTE_STEP.get(interval);
        if (stepValue == null) {
            loadUsdIndexDaily(interval, cb);
            return;
        }
        final int step = stepValue;
        int days = "1m".equals(interval) ? 1 : 5;   // 1分钟只看当日，其它周期多取几天凑根数
        String url = EAST_TRENDS
                + "?secid=" + USD_TRENDS_SECID
                + "&fields1=f1,f2,f3,f4"
                + "&fields2=f51,f52,f53,f54,f55,f56"
                + "&iscr=0&ndays=" + days;
        fetchText(url, headersFor(EAST), body -> {
            List<Tick> ticks = parseEastTrends(body, USD_TRENDS_SECID);
            cb.onResult(aggregateSnapshot(USD, ticks, step, "美元指数"));
        }, () -> loadSinaUsdSnapshot(step, cb), cb);
    }

    /** 新浪外汇快照：美元指数只有一笔实时价，仅够凑出当前这根K线（东财不可用时的兜底） */
    private static void loadSinaUsdSnapshot(int step, StepCallback cb) {
        fetchText(SINA_SNAPSHOT + USD_SINA_CODE, sinaHeaders(), body -> {
            Tick tick = parseSinaForexSnapshot(body);
            if (tick == null) throw new IllegalArgumentException(USD_SINA_CODE + " 暂无有效价格");
            List<Tick> ticks = new ArrayList<>();
            ticks.add(tick);
            cb.onResult(aggregateSnapshot(USD, ticks, step, "美元指数"));
        }, () -> cb.onError("美元指数各接口均不可用"), cb);
    }

    /** 美元指数日线：由欧洲央行汇率反算（免密钥接口，仅有日频工作日数据） */
    private static void loadUsdIndexDaily(String interval, StepCallback cb) {
        String url = FRANKFURTER + "2015-01-01..?base=USD&symbols=" + USDX_CURRENCIES;
        fetchText(url, headersFor(USD), body -> {
            List<Quote> daily = parseFrankfurterUsdx(body);
            if (daily.isEmpty()) throw new IllegalArgumentException("汇率接口无数据");
            cb.onResult(resample(daily, interval));
        }, () -> cb.onError("美元指数日线接口不可用"), cb);
    }

    private static List<String> buildUrls(String name, String code, String interval, int limit) {
        // 季线/年线：各源都没有现成接口，统一先取月线，再由 resample() 合并成季度/年度
        boolean composed = isComposedInterval(interval);
        String request = composed ? "1M" : interval;
        int rows = requestLimit(limit, name);
        if (composed) {
            Integer max = SINGLE_REQUEST_MAX.get(name);
            int factor = "1Y".equals(interval) ? 12 : 3;   // 一年 12 个月，一季 3 个月
            rows = Math.min(max == null ? 640 : max, Math.max(rows, 120) * factor);
        }
        if (TENCENT.equals(name)) {
            String minuteKey = TENCENT_MINUTE_KEY.get(request);
            if (minuteKey != null) {
                return Arrays.asList(
                        TENCENT_MINUTE + "?param=" + code + "," + minuteKey + ",," + rows,
                        TENCENT_MINUTE_MIRROR + "?param=" + code + "," + minuteKey + ",," + rows);
            }
            String dailyKey = TENCENT_DAILY_KEY.get(request);
            if (dailyKey == null) dailyKey = "day";
            String param = "?param=" + code + "," + dailyKey + ",,," + rows + ",qfq";
            return Arrays.asList(TENCENT_KLINE + param, TENCENT_KLINE_MIRROR + param);
        }
        if (EAST.equals(name)) {
            Integer klt = EAST_KLT.get(request);
            String query = "?secid=" + code
                    + "&fields1=f1,f2,f3,f4,f5,f6"
                    + "&fields2=f51,f52,f53,f54,f55,f56,f57"
                    + "&klt=" + (klt == null ? 101 : klt)
                    + "&fqt=1&beg=19900101&end=20500101&lmt=" + rows;
            return Collections.singletonList(EAST_KLINE + query);
        }
        String bar = BINANCE_BAR.get(request);
        String query = "?symbol=" + code + "&interval=" + (bar == null ? request : bar)
                + "&limit=" + Math.min(rows, 1000);
        return Arrays.asList(BINANCE_KLINE + query, BINANCE_KLINE_MIRROR + query);
    }

    /** 季线/年线：没有原生接口，需要拿更细粒度的数据自己合并 */
    private static boolean isComposedInterval(String interval) {
        return "1Q".equals(interval) || "1Y".equals(interval);
    }

    private static HashMap<String, String> headersFor(String name) {
        HashMap<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Device2Device-matkline/1.0");
        if (TENCENT.equals(name)) headers.put("Referer", "https://gu.qq.com/");
        return headers;
    }

    private static HashMap<String, String> sinaHeaders() {
        HashMap<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Device2Device-matkline/1.0");
        headers.put("Referer", "https://finance.sina.com.cn");
        return headers;
    }

    // ------------------------------------------------------------------
    // HTTP：主/镜像地址依次尝试
    // ------------------------------------------------------------------

    private static void fetchAny(Queue<String> urls, HashMap<String, String> headers,
                                 Parser parser, StepCallback cb) {
        if (urls.isEmpty()) {
            cb.onError("接口地址不可用");
            return;
        }
        String url = urls.poll();
        request(url, headers, new TextConsumer() {
            @Override
            public void accept(String body) throws Exception {
                cb.onResult(parser.parse(body));
            }
        }, () -> fetchAny(urls, headers, parser, cb), cb);
    }

    private static void fetchText(String url, HashMap<String, String> headers,
                                  TextConsumer consumer, StepCallback cb) {
        request(url, headers, consumer, () -> cb.onError("请求失败"), cb);
    }

    /** 带回退的取数：请求失败或解析失败都走 retry（用于多源兜底） */
    private static void fetchText(String url, HashMap<String, String> headers,
                                  TextConsumer consumer, Runnable retry, StepCallback cb) {
        request(url, headers, consumer, retry, cb);
    }

    private static void request(String url, HashMap<String, String> headers, TextConsumer consumer,
                                Runnable retry, StepCallback cb) {
        HttpsRequest.executeRequest(url, "GET", headers, null, new HttpsRequest.HttpsRequestCallback() {
            @Override
            public void onSuccess(int responseCode, String response, Map<String, String> respHeaders) {
                try {
                    consumer.accept(response);
                } catch (Exception e) {
                    Log.w(TAG, "解析失败 " + url + " -> " + e.getMessage());
                    retry.run();
                }
            }

            @Override
            public void onFailure(Exception e) {
                retry.run();
            }
        });
    }

    // ------------------------------------------------------------------
    // 解析器：各接口 -> 统一结构
    // ------------------------------------------------------------------

    private static List<Quote> parseKline(String name, String code, String interval, String body)
            throws Exception {
        if (TENCENT.equals(name)) return parseTencent(body, code, interval);
        if (EAST.equals(name)) return parseEast(body);
        return parseBinance(body);
    }

    /** 腾讯: data[code]['qfqday'|'m5']，每行 [时间, 开, 收, 高, 低, 量, ...] */
    private static List<Quote> parseTencent(String body, String code, String interval) throws Exception {
        JSONObject root = new JSONObject(body);
        JSONObject data = root.optJSONObject("data");
        if (data == null || data.length() == 0) {
            throw new IllegalArgumentException("未返回行情数据(code可能不存在)");
        }
        JSONObject block = data.optJSONObject(code);
        if (block == null) {
            Iterator<String> keys = data.keys();
            if (!keys.hasNext()) throw new IllegalArgumentException("返回结构异常");
            block = data.optJSONObject(keys.next());
        }
        if (block == null) throw new IllegalArgumentException("返回结构异常");
        String minuteKey = TENCENT_MINUTE_KEY.get(interval);
        JSONArray rows;
        if (minuteKey != null) {
            rows = block.optJSONArray(minuteKey);
        } else {
            String dailyKey = TENCENT_DAILY_KEY.get(interval);
            if (dailyKey == null) dailyKey = "day";
            rows = block.optJSONArray("qfq" + dailyKey);
            if (rows == null) rows = block.optJSONArray(dailyKey);
        }
        if (rows == null || rows.length() == 0) throw new IllegalArgumentException("无该周期数据");

        List<Quote> quotes = new ArrayList<>(rows.length());
        for (int i = 0; i < rows.length(); i++) {
            JSONArray row = rows.optJSONArray(i);
            if (row == null || row.length() < 6) continue;
            float open = (float) row.optDouble(1, 0);
            float close = (float) row.optDouble(2, 0);
            float high = (float) row.optDouble(3, 0);
            float low = (float) row.optDouble(4, 0);
            float vol = (float) row.optDouble(5, 0);
            // 腾讯无成交额列，退化为收盘价（脚本口径：该列仅展示用）
            quotes.add(new Quote(tencentTime(row.optString(0, "")), open, high, low, close, vol, close));
        }
        return quotes;
    }

    /** 东财: data.klines = ["日期,开,收,高,低,量,额,..."] */
    private static List<Quote> parseEast(String body) throws Exception {
        JSONObject root = new JSONObject(body);
        JSONObject data = root.optJSONObject("data");
        JSONArray rows = data == null ? null : data.optJSONArray("klines");
        if (rows == null || rows.length() == 0) throw new IllegalArgumentException("无K线数据(代码可能不存在)");
        List<Quote> quotes = new ArrayList<>(rows.length());
        for (int i = 0; i < rows.length(); i++) {
            String[] parts = String.valueOf(rows.opt(i)).split(",");
            if (parts.length < 6) continue;
            quotes.add(new Quote(normalizeDay(parts[0]),
                    (float) safe(parts[1]), (float) safe(parts[3]), (float) safe(parts[4]),
                    (float) safe(parts[2]), (float) safe(parts[5]),
                    parts.length > 6 ? (float) safe(parts[6]) : (float) safe(parts[2])));
        }
        return quotes;
    }

    /** 币安: [[开仓时间, 开, 高, 低, 收, 量, ...], ...] */
    private static List<Quote> parseBinance(String body) throws Exception {
        JSONArray rows = new JSONArray(body);
        if (rows.length() == 0) throw new IllegalArgumentException("未返回K线数据");
        List<Quote> quotes = new ArrayList<>(rows.length());
        for (int i = 0; i < rows.length(); i++) {
            JSONArray row = rows.optJSONArray(i);
            if (row == null || row.length() < 6) continue;
            quotes.add(new Quote(millisTime(row.optLong(0, 0L)),
                    (float) row.optDouble(1, 0), (float) row.optDouble(2, 0),
                    (float) row.optDouble(3, 0), (float) row.optDouble(4, 0),
                    (float) row.optDouble(5, 0), row.length() > 7 ? (float) row.optDouble(7, 0) : 0f));
        }
        return quotes;
    }

    /** 新浪国内期货: [{'d','o','h','l','c','v','p'}, ...] */
    private static List<Quote> parseSinaInner(JSONArray rows, String code) throws Exception {
        if (rows == null || rows.length() == 0) throw new IllegalArgumentException("无K线数据(" + code + ")");
        List<Quote> quotes = new ArrayList<>(rows.length());
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            float volume = (float) row.optDouble("v", 0);
            quotes.add(new Quote(normalizeDay(row.optString("d", "")),
                    (float) row.optDouble("o", 0), (float) row.optDouble("h", 0),
                    (float) row.optDouble("l", 0), (float) row.optDouble("c", 0),
                    volume, (float) row.optDouble("p", row.optDouble("c", 0))));
        }
        return quotes;
    }

    /** 新浪国际期货日线: [{'date','open','high','low','close','volume'}] */
    private static List<Quote> parseSinaGlobal(JSONArray rows, String code) throws Exception {
        if (rows == null || rows.length() == 0) throw new IllegalArgumentException("无K线数据(" + code + ")");
        List<Quote> quotes = new ArrayList<>(rows.length());
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            float close = (float) row.optDouble("close", 0);
            quotes.add(new Quote(normalizeDay(row.optString("date", "")),
                    (float) row.optDouble("open", 0), (float) row.optDouble("high", 0),
                    (float) row.optDouble("low", 0), close,
                    (float) row.optDouble("volume", 0), close));
        }
        return quotes;
    }

    /** 剥离新浪 JSONP 外壳 var _XAU(...); */
    private static String parseJsonp(String text) throws Exception {
        Matcher matcher = JSONP_BODY.matcher(text.trim());
        if (!matcher.find()) throw new IllegalArgumentException("JSONP 响应格式异常");
        return matcher.group(1);
    }

    /** 快照: var hq_str_hf_XAU="最新价,...,时间,昨收,...,日期,名称"; */
    private static Tick parseSinaSnapshot(String text) {
        Matcher matcher = SNAPSHOT_BODY.matcher(text == null ? "" : text);
        if (!matcher.find()) return null;
        String[] parts = matcher.group(1).split(",");
        if (parts.length < 13) return null;
        float price = (float) safe(parts[0]);
        if (price <= 0) return null;
        String when = parts[12].trim() + " " + parts[6].trim();
        if (!looksLikeTime(when)) return null;
        return new Tick(when, price);
    }

    /** 国际金分时线: payload['minLine_1d'] -> (完整时间, 价格) */
    private static List<Tick> parseSinaMinline(String json, String code) throws Exception {
        JSONObject payload = new JSONObject(json);
        JSONArray rows = payload.optJSONArray("minLine_1d");
        if (rows == null || rows.length() == 0) throw new IllegalArgumentException("分时接口无数据(" + code + ")");
        List<Tick> ticks = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONArray row = rows.optJSONArray(i);
            if (row == null) continue;
            String when;
            double price;
            if (row.length() >= 10) {
                when = row.optString(9, "");
                price = row.optDouble(5, 0);
            } else if (row.length() >= 6) {
                when = row.optString(5, "");
                price = row.optDouble(1, 0);
            } else {
                continue;
            }
            if (price > 0 && looksLikeTime(when)) ticks.add(new Tick(when.trim(), (float) price));
        }
        return ticks;
    }

    /** 东财分时(1分钟)：data['trends'] 每行为 "时间,开,收,高,低,量,额,均价"，只有最新价可用 */
    private static List<Tick> parseEastTrends(String body, String code) throws Exception {
        JSONObject root = new JSONObject(body);
        JSONObject data = root.optJSONObject("data");
        JSONArray rows = data == null ? null : data.optJSONArray("trends");
        if (rows == null || rows.length() == 0) throw new IllegalArgumentException("分时接口无数据(" + code + ")");
        List<Tick> ticks = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            String[] parts = String.valueOf(rows.opt(i)).split(",");
            if (parts.length < 3) continue;
            float price = (float) safe(parts[2]);
            if (price > 0) ticks.add(new Tick(parts[0].trim().substring(0, Math.min(16, parts[0].trim().length())) + ":00", price));
        }
        if (ticks.isEmpty()) throw new IllegalArgumentException("分时接口无有效价格(" + code + ")");
        return ticks;
    }

    /** 新浪外汇快照: var hq_str_DINIW="时间,开,昨收,...,最高,最低,最新价,名称,日期"，只取最新价 */
    private static Tick parseSinaForexSnapshot(String text) {
        Matcher matcher = SNAPSHOT_BODY.matcher(text == null ? "" : text);
        if (!matcher.find()) return null;
        String[] parts = matcher.group(1).split(",");
        if (parts.length < 11) return null;
        float price = (float) safe(parts[8]);
        if (price <= 0) return null;
        String when = parts[10].trim() + " " + parts[0].trim();
        if (!looksLikeTime(when)) return null;
        return new Tick(when, price);
    }

    /** 欧洲央行汇率时序: rates = {"2026-09-18": {"EUR":0.87, "JPY":157.8, ...}, ...} */
    private static List<Quote> parseFrankfurterUsdx(String body) throws Exception {
        JSONObject root = new JSONObject(body);
        JSONObject rates = root.optJSONObject("rates");
        if (rates == null || rates.length() == 0) throw new IllegalArgumentException("汇率接口无数据");
        List<String> days = new ArrayList<>();
        Iterator<String> keys = rates.keys();
        while (keys.hasNext()) days.add(keys.next());
        Collections.sort(days);
        List<Quote> quotes = new ArrayList<>(days.size());
        for (String day : days) {
            JSONObject row = rates.optJSONObject(day);
            if (row == null) continue;
            float usdx = usdxFromRates(row);
            if (usdx <= 0) continue;
            // 汇率源只有收盘价一个值，四价同值（与脚本对无OHLC源的处理一致）
            quotes.add(new Quote(day + " 00:00:00", usdx, usdx, usdx, usdx, 0f, usdx));
        }
        if (quotes.isEmpty()) throw new IllegalArgumentException("汇率数据不完整");
        return quotes;
    }

    /** ICE 官方公式：50.14348112 × EURUSD^-0.576 × USDJPY^0.136 × GBPUSD^-0.119 × USDCAD^0.091 × USDSEK^0.042 × USDCHF^0.036 */
    private static float usdxFromRates(JSONObject row) {
        double eur = row.optDouble("EUR", 0);
        double jpy = row.optDouble("JPY", 0);
        double gbp = row.optDouble("GBP", 0);
        double cad = row.optDouble("CAD", 0);
        double sek = row.optDouble("SEK", 0);
        double chf = row.optDouble("CHF", 0);
        if (eur <= 0 || jpy <= 0 || gbp <= 0 || cad <= 0 || sek <= 0 || chf <= 0) return 0f;
        double value = USDX_BASE
                * Math.pow(1.0 / eur, -0.576)
                * Math.pow(jpy, 0.136)
                * Math.pow(1.0 / gbp, -0.119)
                * Math.pow(cad, 0.091)
                * Math.pow(sek, 0.042)
                * Math.pow(chf, 0.036);
        return (float) value;
    }

    /**
     * 由更细粒度的数据合并出周/月/季/年线：开取首根、收取末根、高低取区间极值。
     * 汇率源只有日频，周/月线自行合并；季线/年线各源都没有现成接口，
     * 腾讯/东财/币安走「月线 -> 季/年」，其余源走「日线 -> 季/年」。
     */
    private static List<Quote> resample(List<Quote> quotes, String interval) {
        boolean weekly = "1w".equals(interval);
        boolean monthly = "1M".equals(interval);
        boolean quarterly = "1Q".equals(interval);
        boolean yearly = "1Y".equals(interval);
        if (!weekly && !monthly && !quarterly && !yearly) return quotes;
        LinkedHashMap<String, float[]> bars = new LinkedHashMap<>();
        LinkedHashMap<String, String> stamps = new LinkedHashMap<>();
        for (Quote quote : quotes) {
            String key;
            if (monthly) key = quote.time.substring(0, Math.min(7, quote.time.length()));
            else if (quarterly) key = quarterKey(quote.time);
            else if (yearly) key = yearKey(quote.time);
            else key = weekKey(quote.time);
            float[] bar = bars.get(key);
            if (bar == null) {
                bars.put(key, new float[]{quote.open, quote.high, quote.low, quote.close});
            } else {
                if (quote.high > bar[1]) bar[1] = quote.high;
                if (quote.low < bar[2]) bar[2] = quote.low;
                bar[3] = quote.close;
            }
            stamps.put(key, quote.time);
        }
        List<Quote> out = new ArrayList<>(bars.size());
        for (Map.Entry<String, float[]> entry : bars.entrySet()) {
            float[] bar = entry.getValue();
            out.add(new Quote(stamps.get(entry.getKey()), bar[0], bar[1], bar[2], bar[3], 0f, bar[3]));
        }
        return out;
    }

    /** 季线分组键：2024-Q1（按自然季度，1-3 月为 Q1） */
    private static String quarterKey(String time) {
        if (time == null || time.length() < 7) return time == null ? "" : time;
        int year = toInt(time.substring(0, 4), 0);
        int month = toInt(time.substring(5, 7), 1);
        return year + "-Q" + ((Math.max(1, Math.min(12, month)) - 1) / 3 + 1);
    }

    /** 年线分组键：2024 */
    private static String yearKey(String time) {
        return time == null ? "" : time.substring(0, Math.min(4, time.length()));
    }

    private static int toInt(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String weekKey(String time) {
        try {
            Calendar calendar = Calendar.getInstance();
            calendar.setTime(new SimpleDateFormat(FULL_FMT, Locale.US).parse(time));
            return calendar.get(Calendar.YEAR) + "-" + calendar.get(Calendar.WEEK_OF_YEAR);
        } catch (Exception e) {
            return time.length() >= 10 ? time.substring(0, 10) : time;
        }
    }

    private static boolean looksLikeTime(String when) {
        return when != null && when.length() >= 16
                && when.charAt(4) == '-' && when.charAt(7) == '-';
    }

    /** 分时/快照聚合器，按源保留（脚本 AGGREGATORS）：刷新时逐笔累积出分钟K线 */
    private static final Map<String, MinuteAggregator> AGGREGATORS = new HashMap<>();

    /** 把分时/快照价格按 step 分钟聚合成K线（该品类没有分钟K线接口） */
    private static List<Quote> aggregateSnapshot(String source, List<Tick> ticks, int step, String name) {
        MinuteAggregator aggregator = AGGREGATORS.get(source);
        if (aggregator == null) {
            aggregator = new MinuteAggregator(1000);
            AGGREGATORS.put(source, aggregator);
        }
        for (Tick tick : ticks) {
            aggregator.push(floorMinute(tick.time, step), tick.price);
        }
        if (aggregator.isEmpty()) throw new IllegalArgumentException(name + " 暂无有效价格");
        return aggregator.quotes();
    }

    /** 单根K线内部结构 [开, 高, 低, 收]；超过 maxBars 时丢掉最早的（脚本 MinuteAggregator） */
    private static final class MinuteAggregator {
        private final LinkedHashMap<String, float[]> bars = new LinkedHashMap<>();
        private final int maxBars;

        MinuteAggregator(int maxBars) {
            this.maxBars = maxBars;
        }

        void push(String when, float price) {
            if (price <= 0) return;
            float[] bar = bars.get(when);
            if (bar == null) {
                bars.put(when, new float[]{price, price, price, price});
                if (bars.size() > maxBars) {
                    Iterator<String> it = bars.keySet().iterator();
                    it.next();
                    it.remove();
                }
                return;
            }
            if (price > bar[1]) bar[1] = price;
            if (price < bar[2]) bar[2] = price;
            bar[3] = price;
        }

        boolean isEmpty() {
            return bars.isEmpty();
        }

        /** 输出按时间升序的K线（分时聚合无成交量，脚本同口径） */
        List<Quote> quotes() {
            List<String> keys = new ArrayList<>(bars.keySet());
            Collections.sort(keys);
            List<Quote> out = new ArrayList<>(keys.size());
            for (String key : keys) {
                float[] bar = bars.get(key);
                out.add(new Quote(key, bar[0], bar[1], bar[2], bar[3], 0f, bar[3]));
            }
            return out;
        }
    }

    /** 把 'YYYY-MM-DD HH:MM:SS' 向下取整到 step 分钟 */
    private static String floorMinute(String when, int step) {
        String text = when.trim();
        try {
            Date date = new SimpleDateFormat(FULL_FMT, Locale.US).parse(text.substring(0, 19));
            Calendar calendar = Calendar.getInstance();
            calendar.setTime(date);
            calendar.set(Calendar.MINUTE, calendar.get(Calendar.MINUTE) / step * step);
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MILLISECOND, 0);
            return new SimpleDateFormat(FULL_FMT, Locale.US).format(calendar.getTime());
        } catch (Exception e) {
            return text.length() >= 16 ? text.substring(0, 16) + ":00" : text;
        }
    }

    private static final class Tick {
        final String time;
        final float price;

        Tick(String time, float price) {
            this.time = time;
            this.price = price;
        }
    }

    // ------------------------------------------------------------------
    // 标的与时间归一化
    // ------------------------------------------------------------------

    /** 周期写法归一化（day/d/week/hour...） */
    public static String normalizeInterval(String interval) {
        String text = (interval == null ? "" : interval).trim();
        if (text.isEmpty()) return "1d";
        String alias = INTERVAL_ALIAS.get(text.toLowerCase(Locale.US));
        if (alias != null) return alias;
        if (text.length() > 1) {   // 月/季/年的大写尾缀要保住（1M / 1Q / 1Y）
            char last = text.charAt(text.length() - 1);
            if (last == 'M' || last == 'Q' || last == 'Y') {
                return text.substring(0, text.length() - 1) + last;
            }
        }
        return text.toLowerCase(Locale.US);
    }

    private static String normalizeSymbol(String name, String symbol) {
        String text = symbol == null ? "" : symbol.trim();
        if (text.isEmpty()) return defaultSymbol(name);
        if (COMMODITY_SOURCES.contains(name)) {
            String alias = COMMODITY_ALIASES.get(text.toLowerCase(Locale.US));
            return alias != null ? alias : text.toUpperCase(Locale.US);
        }
        if (EAST.equals(name)) return eastmoneySecid(text);
        if (BINANCE.equals(name)) {
            return text.toUpperCase(Locale.US).replace("-", "").replace("/", "");
        }
        return tencentCode(text);
    }

    /** 把 600519 / sh600519 统一成腾讯的 前缀+代码 */
    static String tencentCode(String symbol) {
        String text = symbol.trim().toLowerCase(Locale.US);
        if (text.startsWith("sh") || text.startsWith("sz") || text.startsWith("bj")
                || text.startsWith("nf_") || text.startsWith("hf_")) {
            return text;
        }
        String digits = digitsOf(text);
        if (digits.length() == 6) {
            char first = digits.charAt(0);
            if (first == '6' || first == '9') return "sh" + digits;
            if (first == '0' || first == '3') return "sz" + digits;
            if (first == '4' || first == '8') return "bj" + digits;
            return "sh" + digits;
        }
        return text;
    }

    /** 把 600519 / sh600519 统一成东财的 市场.代码（1=沪，0=深） */
    static String eastmoneySecid(String symbol) {
        String text = symbol.trim();
        String low = text.toLowerCase(Locale.US);
        if (text.contains(".") && (low.startsWith("0.") || low.startsWith("1."))) return text;
        String digits = digitsOf(low);
        if (digits.length() == 6) {
            char first = digits.charAt(0);
            String market = (first == '6' || first == '9' || low.startsWith("sh")) ? "1" : "0";
            return market + "." + digits;
        }
        return text;
    }

    private static String digitsOf(String text) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') sb.append(c);
        }
        return sb.toString();
    }

    /** 腾讯时间：分钟线 'YYYYMMDDHHMM'，日线及以上 'YYYY-MM-DD' */
    private static String tencentTime(String raw) {
        String text = raw.trim();
        if (text.length() >= 12) {
            try {
                Date date = TENCENT_MINUTE_FMT.parse(text.substring(0, 12));
                return new SimpleDateFormat(FULL_FMT, Locale.US).format(date);
            } catch (Exception ignored) {
                return text;
            }
        }
        String day = text.replace('/', '-');
        if (day.length() > 10) day = day.substring(0, 10);
        return day + " 00:00:00";
    }

    /** 东财/新浪的日期串补齐成 YYYY-MM-DD HH:MM:SS */
    private static String normalizeDay(String raw) {
        String text = raw.trim().replace('/', '-');
        if (text.length() <= 10) return text + " 00:00:00";
        if (text.length() == 16) return text + ":00";
        return text;
    }

    /** 毫秒时间戳 -> YYYY-MM-DD HH:MM:SS */
    private static String millisTime(long stamp) {
        long value = stamp > 1e11 ? stamp : stamp * 1000L;
        return new SimpleDateFormat(FULL_FMT, Locale.US).format(new Date(value));
    }

    private static double safe(String value) {
        try {
            return Double.parseDouble(value.trim());
        } catch (Exception e) {
            return 0.0;
        }
    }

    /** limit<=0 表示不限条数，此时退回该数据源单次请求上限（脚本 request_limit） */
    private static int requestLimit(int limit, String source) {
        if (limit > 0) return limit;
        Integer max = SINGLE_REQUEST_MAX.get(source);
        return max == null ? 640 : max;
    }

    /** 时间容忍：超过当前时刻 24 小时的K线视为脏数据（脚本 FUTURE_TOLERANCE_HOURS） */
    private static final long FUTURE_TOLERANCE_MS = 24 * 60 * 60 * 1000L;

    /**
     * 丢掉时间明显超前于当前时刻的K线（脚本 drop_future_points）。
     * 新浪国内期货分钟线末端偶尔混入「下一交易日」的占位行，会造成图上多出一根未来K线。
     * 日期无法解析时一律保留。
     */
    private static List<Quote> dropFuturePoints(List<Quote> quotes) {
        if (quotes == null || quotes.isEmpty()) return quotes;
        long horizon = System.currentTimeMillis() + FUTURE_TOLERANCE_MS;
        List<Quote> kept = new ArrayList<>(quotes.size());
        for (Quote quote : quotes) {
            Long stamp = parseStamp(quote.time);
            if (stamp == null || stamp <= horizon) kept.add(quote);
        }
        return kept.size() == quotes.size() ? quotes : kept;
    }

    /** 'YYYY-MM-DD HH:MM:SS' -> 毫秒；解析失败返回 null（日线等无时间的日期串） */
    private static Long parseStamp(String time) {
        if (time == null || time.length() < 19) return null;
        try {
            return new SimpleDateFormat(FULL_FMT, Locale.US).parse(time.substring(0, 19)).getTime();
        } catch (Exception e) {
            return null;
        }
    }

    /** 只保留最后 limit 根K线（limit<=0 表示不限） */
    private static List<Quote> tail(List<Quote> quotes, int limit) {
        if (limit > 0 && quotes.size() > limit) {
            return new ArrayList<>(quotes.subList(quotes.size() - limit, quotes.size()));
        }
        return quotes;
    }

    private static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String item : items) {
            if (sb.length() > 0) sb.append("/");
            sb.append(item);
        }
        return sb.toString();
    }
}
