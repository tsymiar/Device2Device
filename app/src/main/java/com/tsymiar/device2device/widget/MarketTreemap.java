package com.tsymiar.device2device.widget;

import com.tsymiar.device2device.market.CapRow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * treemap 排版器：把一串市值行按「面积 = 市值」摆成一片互相贴合、尽量方的马赛克。
 *
 * 摆法是 squarified（Bruls / Huizing / van Wijk）：按市值降序一行行填，每行铺满当前矩形的
 * 一条边，行里每只票沿另一条边占「自己市值 ÷ 这一行市值」，剩下的矩形接着分行。
 *
 * 行内还再混一次方向（见 {@link #segments}）：连续几只瘦的（一字排开会成竖着的细条）
 * 叠成一摞，方向与这一行交叉 —— 每一行因此都是横竖混着的，
 * 宽的票各自占一段、瘦的三两只合起来叠成一摞，叠出来的那几只变宽变矮，又方了回来。
 *
 * 不用「按市值对半分」：那样份额小的票被挤成一列一像素宽的细条，面积虽对却「不紧凑」，
 * 名字也塞不进去。同一份数据实测：对半切长宽比中位 1.8、最差 11、最瘦 20px；
 * squarified 中位 1.1、最差 2.8、最瘦 30px 以上。正方形是面积一定时最好读的形状。
 *
 * 各格面积之和因此恰好等于这块矩形 —— 既不超出边框，也正好铺满。
 *
 * 混方向发生在两处，所以马赛克一眼看上去是横竖交错的，不是一排排齐刷刷的条带：
 * ①行内（{@link #segments}）：一行里连续几只瘦的叠成一摞，摞的方向与这一行交叉；
 * ②块与块之间（{@link #beside}）：贪心分行的最后一道常常薄成一根线（数据越偏越明显），
 *   薄道里的格子只能是扁条、名字一个都摆不下 —— 把它改成旁边一块紧凑的区域，那几只票
 *   在里面自己分行，格子就方回来了。实测最差长宽比从 4.7 降到 2.0。
 *
 * 每深一层就多一层嵌套 LinearLayout，RemoteViews 的嵌套有代价，所以混方向只到「块」这一层，
 * 不再无休止地递归分下去。渲染见 {@code MarketHeatWidgetProvider}。
 */
public final class MarketTreemap {

    /**
     * 多薄算「薄成一道」：比上一道还不到这个比例就当它是一根线，改排到旁边去（见 {@link #beside}）。
     * 60% 是量出来的：再宽一点，薄道里的格子长宽比就已经在 2 以上了；再窄一点，
     * 正常的最后一道也会被拽走，白挨一次形状上的损失。
     */
    private static final float SLIVER_RATIO = 0.6f;

    private MarketTreemap() {
    }

    /** 排版树上的一个节点：叶子是一片格子，内部节点是一行（或一列）容器 */
    public static final class Node {
        /** 叶子才有：该格对应哪只票 */
        public final CapRow row;
        /** 内部节点才有：这一行（列）里的孩子，按摆放顺序排列 */
        public final List<Node> children;
        /** true：孩子们左右排开；false：上下排开 */
        public final boolean alongWidth;
        /** 在父容器里占几成；同一个父节点下孩子们相加恰为 1 */
        public final float weight;

        private Node(CapRow row, List<Node> children, boolean alongWidth, float weight) {
            this.row = row;
            this.children = children;
            this.alongWidth = alongWidth;
            this.weight = weight;
        }

        public boolean isLeaf() {
            return row != null;
        }
    }

    /**
     * @param rows     市值行；市值不为正的会被丢掉 —— 没有面积也就无处可摆
     * @param widthPx  摆放矩形的宽（px）
     * @param heightPx 摆放矩形的高（px）
     * @param topRows  顶层不许把薄道拽到旁边去（见 {@link #beside}）：调用方要把顶层摊平成
     *                  「一行行横着排」的条目（见 {@code MarketHeatWidgetService}），
     *                  顶层横着摆的一块摊不平。换向照换（多套一层竖容器），摊得平
     * @return 排版树的根；没什么可摆时给 null
     */
    public static Node layout(List<CapRow> rows, float widthPx, float heightPx, boolean topRows) {
        List<CapRow> usable = new ArrayList<>();
        if (rows != null) {
            for (CapRow row : rows) {
                if (row != null && row.totalCap > 0d) usable.add(row);
            }
        }
        if (usable.isEmpty() || widthPx <= 1f || heightPx <= 1f) return null;
        // squarified 的前提就是按市值降序：大的先排，每一行才方得起来
        return rows(byCap(usable), 1f, widthPx, heightPx, topRows);
    }

    private static List<CapRow> byCap(List<CapRow> rows) {
        List<CapRow> sorted = new ArrayList<>(rows);
        Collections.sort(sorted, new Comparator<CapRow>() {
            @Override
            public int compare(CapRow a, CapRow b) {
                return Double.compare(b.totalCap, a.totalCap);
            }
        });
        return sorted;
    }

    /** 把这块矩形切成一行行：每行铺满一条边，剩下的矩形接着分行。一行的厚度 = 它的面积 ÷
        铺满那条边的长度，各行之厚相加恰好是整块矩形 */
    private static Node rows(List<CapRow> items, float weight, float wPx, float hPx) {
        return rows(items, weight, wPx, hPx, false);
    }

    private static Node rows(List<CapRow> items, float weight, float wPx, float hPx, boolean topRows) {
        // 竖条（左右并排）还是横条（上下堆叠）：竖向控件（h>=w）走竖条，方块收窄、拉长，
        // 正好填满较高的那一边；横向控件对称走横条。
        // 顶层可以要求一律横着分行（topRows）：那一层拆出来的行要当滚动条目用，
        // 竖着排的几列拆不成条目 —— 代价是画布偏高时方形度差一点，由 rowLength 自己权衡
        boolean along = topRows ? false : hPx >= wPx;
        double total = sum(items, items.size());
        if (items.size() == 1 || total <= 0d) {
            return new Node(items.get(0), null, false, weight);
        }
        List<List<CapRow>> lines = new ArrayList<>();
        List<Double> caps = new ArrayList<>();
        int from = 0;
        double used = 0d;
        float w = wPx;
        float h = hPx;
        while (from < items.size()) {
            // 剩下的矩形一旦换向（变高 / 变宽），这一层就到此为止：同一个 LinearLayout
            // 只能朝一个方向排，换向得换一层 —— 见下面的 head / tail。
            // 换向照换（顶层也要当滚动条目用，调用方会把一摞行摊平，见 MarketHeatWidgetService），
            // 但顶层不把薄道拽到旁边去（见 finish 里的 topRows）—— 那样顶层就成了横着摆的一块，
            // 摊不平了
            if (from > 0 && (w >= h) != along) break;
            // 判据一律按整块矩形算：条带的厚度 = 整块的厚度 × 这一行的市值占比，
            // 与「已经切掉了几行」无关（剩下的矩形自己会变小，用剩余量当分母就偏了）
            int n = rowLength(items, from, total, along ? hPx : wPx, along ? wPx : hPx);
            List<CapRow> line = new ArrayList<>(items.subList(from, from + n));
            double cap = sum(line, line.size());
            lines.add(line);
            caps.add(cap);
            from += n;
            used += cap;
            float share = (float) (cap / total);
            if (along) w = Math.max(1f, w - wPx * share);
            else h = Math.max(1f, h - hPx * share);
        }
        // 这一层：行铺满 side 那条边、行厚从 cross 那条边里扣
        float side = along ? hPx : wPx;
        float cross = along ? wPx : hPx;
        List<CapRow> rest = new ArrayList<>(items.subList(from, items.size()));
        double restCap = total - used;
        return finish(lines, caps, used, total, weight, along, side, cross, rest, restCap, w, h, topRows);
    }

    /**
     * 收尾：把「已经排好的几行」和「剩下的那批」合成这一层的节点。
     *
     * 两条路：剩下的那批市值不小，就照老规矩接在后面（换向了，剩下的矩形自己再分行）；
     * 剩下的那批薄成一道，就改排到旁边去（见 {@link #beside}）—— 薄道里的格子只能是扁条。
     */
    private static Node finish(List<List<CapRow>> lines, List<Double> caps, double used, double total,
                               float weight, boolean along, float side, float cross,
                               List<CapRow> rest, double restCap, float w, float h, boolean topRows) {
        // 顶层不许横着摆（那些行要当滚动条目用），所以薄道不往旁边放，交回 mergeSlivers 并回去重分
        if (!rest.isEmpty() && !topRows
                && sliver(restCap, caps.isEmpty() ? 0d : caps.get(caps.size() - 1))) {
            return beside(lines, caps, used, weight, along, side, cross, rest, restCap);
        }
        if (rest.isEmpty()) {
            return stack(lines, caps, used, weight, along, side, cross, topRows);
        }
        // 中途换向了：已经排好的这几行是一块（只占掉 cross 的一份），剩下的矩形自己再分行
        Node head = stack(lines, caps, used, (float) (used / total), along, side,
                cross * (float) (used / total), topRows);
        Node tail = rows(rest, (float) (restCap / total), w, h);
        List<Node> kids = new ArrayList<>(2);
        kids.add(head);
        kids.add(tail);
        return new Node(null, kids, along, weight);
    }

    /**
     * 排好的一串行码成一块：{@code along} 是这一块的方向（与行方向交叉），weights 在这一块里归一。
     * 每一行的厚度 = {@code cross} × 这一行的市值占这一块的比 —— 摆的时候一条边铺满
     * {@code side}、另一条边就只有这么厚，行里怎么混方向按这两个数算（见 {@link #strip}）。
     * 各行厚度相加恰好是 {@code cross}：这一块就是这样被几行铺满的。
     */
    private static Node stack(List<List<CapRow>> lines, List<Double> caps, double used, float weight,
                              boolean along, float side, float cross, boolean topRows) {
        // 最后一行薄成一道：把它从这一摞里摘出来，改排到旁边去（见 beside）。
        // 顶层不行 —— 那一层拆出来的行要当滚动条目用，横着摆的一块拆不成条目，
        // 所以顶层走 mergeSlivers（并回上一行重新分）
        if (!topRows && lines.size() >= 2
                && sliver(caps.get(lines.size() - 1), caps.get(lines.size() - 2))) {
            List<CapRow> last = lines.remove(lines.size() - 1);
            double lastCap = caps.remove(caps.size() - 1);
            return beside(lines, caps, used - lastCap, weight, along, side, cross, last, lastCap);
        }
        if (lines.size() == 1) {
            return strip(lines.get(0), weight, !along, side, cross);
        }
        List<Node> kids = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            kids.add(strip(lines.get(i), (float) (caps.get(i) / used), !along, side,
                    cross * (float) (caps.get(i) / used)));
        }
        return new Node(null, kids, along, weight);
    }

    /**
     * 薄成一道的那批，从「横在最后的一道」改成「竖在旁边的一块」。
     *
     * 薄道（行厚 25px、行长 696px）里的格子只能是扁条 —— 一只 100×25 的格子，四个字的名字
     * 连一行都排不下，于是整格只剩一块颜色。改成旁边一块紧凑的区域就不一样了：
     * 这块地的面积还是那一份（从这条边上切走 {@code side × 市值占比}），但形状是方的，
     * 那几只票在里面自己分行，格子就方回来了。
     *
     * 于是这一层是横竖混着的：一侧还是原来那几行，另一侧是一块自己排的小区域。
     * 剩下那几行仍然沿原来的方向排、并且铺满自己那块地（不再占满整条边，但厚度仍是按市值分）——
     * 两块地相加恰好等于这一层，既不重叠也不缺口。
     */
    private static Node beside(List<List<CapRow>> lines, List<Double> caps, double usedHead,
                               float weight, boolean along, float side, float cross,
                               List<CapRow> rest, double restCap) {
        double total = usedHead + restCap;
        float tailLen = Math.max(1f, side * (float) (restCap / total));
        Node head = stack(lines, caps, usedHead, (float) (usedHead / total), along,
                Math.max(1f, side - tailLen), cross, false);
        // 尾块自己那块地：一条边从这条边上切走，另一条边还是整条 —— 形状由它自己的行数定
        Node tail = rows(rest, (float) (restCap / total),
                along ? cross : tailLen, along ? tailLen : cross);
        List<Node> kids = new ArrayList<>(2);
        kids.add(head);
        kids.add(tail);
        // 头几行沿原来的方向，尾块在旁边 —— 这一层整体就是横竖混着的
        return new Node(null, kids, !along, weight);
    }

    /**
     * 这一道是不是薄成一根线了：比上一道还不到 {@link #SLIVER_RATIO} 就当它薄。
     * 厚度与市值成正比（同一层里），所以直接比市值就够，不用折算像素。
     */
    private static boolean sliver(double cap, double prevCap) {
        return prevCap > 0d && cap < prevCap * SLIVER_RATIO;
    }

    /**
     * 一行：里面的票沿这条边一字排开（{@code side} 是这条边有多长、{@code thick} 是这一行多厚），
     * 连续几只瘦的则并成一段换个方向摆（见 {@link #segments}），weight 按市值给（在这一行内归一）。
     */
    private static Node strip(List<CapRow> line, float weight, boolean along,
                              float side, float thick) {
        if (line.size() == 1) return new Node(line.get(0), null, false, weight);
        double total = sum(line, line.size());
        List<Node> kids = new ArrayList<>(line.size());
        for (List<CapRow> seg : segments(line, side, thick)) {
            float w = total > 0d ? (float) (sum(seg, seg.size()) / total) : 1f / line.size();
            kids.add(seg.size() == 1 ? new Node(seg.get(0), null, false, w)
                    : nest(seg, w, !along));
        }
        return new Node(null, kids, along, weight);
    }

    /** 一小段（两只以上）摆成一个方向与所在行交叉的子块：里面的票按市值分量，weight 在段内归一 */
    private static Node nest(List<CapRow> seg, float weight, boolean along) {
        double total = sum(seg, seg.size());
        List<Node> kids = new ArrayList<>(seg.size());
        for (CapRow row : seg) {
            kids.add(new Node(row, null, false,
                    total > 0d ? (float) (Math.max(0d, row.totalCap) / total) : 1f / seg.size()));
        }
        return new Node(null, kids, along, weight);
    }

    /**
     * 把一行切成几段：连续几只「瘦」的（沿行方向一字排开只有几像素宽）并成一段。
     *
     * 一字排开时这一行里越靠后的票越窄：宽 e、高 T，长宽比 T/e，三四十像素宽的就是一根竖条，
     * 名字摆不进去。改成 k 只叠成一摞（占的宽度还是这几只的宽度之和 W、高度各分 T/k）：
     * 每只变成 W 宽 × (T·e_i/W) 高，长宽比是 max(W²/(T·e_i), T·e_i/W²) —— 与一字排开时的
     * T/e_i 相比，只要 W 还没这一行厚就一定更方（对这一摞里每一只都成立），W = T/k 时最方。
     * 所以判据是「并起来还没这一行厚」。
     *
     * 面积一点没变：段里每只占的仍是 W × T·(自己市值 ÷ 段内市值)，与一字排开时 e × T 相等。
     */
    private static List<List<CapRow>> segments(List<CapRow> line, float side, float thick) {
        double total = sum(line, line.size());
        List<List<CapRow>> out = new ArrayList<>();
        List<CapRow> cur = new ArrayList<>();
        float width = 0f;
        for (CapRow row : line) {
            float ext = total > 0d ? side * (float) (Math.max(0d, row.totalCap) / total)
                    : side / Math.max(1, line.size());
            if (!cur.isEmpty() && width + ext > thick) {
                out.add(cur);                       // 并起来比这一行还厚了，这一段到这儿为止
                cur = new ArrayList<>();
                width = 0f;
            }
            cur.add(row);
            width += ext;
        }
        out.add(cur);
        return out;
    }

    /**
     * 这一行放几只：从 {@code from} 开始取前缀，哪个前缀里「最瘦的那个格子」长宽比最小就要哪个。
     *
     * @param total 整块矩形的市值和
     * @param side  这一行铺满的那条边有多长（行里每只票沿它排开）
     * @param cross 行厚度的方向有多长（这一行占多厚，从这条边里扣）
     */
    private static int rowLength(List<CapRow> items, int from, double total,
                                 float side, float cross) {
        int best = 1;
        double run = 0d;
        float bestWorst = Float.MAX_VALUE;
        for (int n = 1; from + n <= items.size(); n++) {
            run += Math.max(0d, items.get(from + n - 1).totalCap);
            if (run <= 0d || total <= 0d) break;
            float thick = cross * (float) (run / total);
            if (thick < 1f) break;                      // 这一行已经薄到画不出来，别再往里塞
            float worst = 0f;
            for (int i = from; i < from + n; i++) {
                float ext = side * (float) (Math.max(0d, items.get(i).totalCap) / run);
                if (ext < 1f) {                          // 该标的已经瘦成一条线
                    worst = Float.MAX_VALUE;
                    break;
                }
                worst = Math.max(worst, Math.max(thick / ext, ext / thick));
            }
            if (worst <= bestWorst) {
                bestWorst = worst;
                best = n;
            } else if (n > best) {
                break;                                   // 越加越瘦长，到此为止
            }
        }
        return Math.max(1, Math.min(best, items.size() - from));
    }

    private static double sum(List<CapRow> items, int count) {
        int limit = Math.min(count, items.size());
        double total = 0d;
        for (int i = 0; i < limit; i++) total += Math.max(0d, items.get(i).totalCap);
        return total;
    }
}
