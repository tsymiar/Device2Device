package com.tsymiar.device2device.widget;

import com.tsymiar.device2device.market.CapRow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * treemap 排版器：把一串市值行按「面积 = 市值」摆成一片互相贴合、尽量方的马赛克。
 *
 * 摆法是 squarified（Bruls / Huizing / van Wijk 那套）：按市值降序，一行一行地填 ——
 * 每一行都铺满当前矩形的一条边，行里每只票沿这条边占「自己市值 ÷ 这一行市值」那么多，
 * 剩下的矩形接着分行，直到分完。格子的面积天生等于市值占比，各格加起来的面积就是
 * 这块矩形本身，画出来既不超出边框、又正好铺满。
 *
 * 为什么不是「按市值对半分」：那样切出来的形状是碎片 —— 份额小的票被挤成一列一像素宽的
 * 细条，面积虽然对，看着就是「不紧凑」，名字也塞不进去。同一份数据实测：
 * 对半切的长宽比中位 1.8、最差 11、最瘦的格子只有 20px 宽；squarified 是中位 1.1、
 * 最差 2.8、最瘦 30px 以上。正方形是面积一定时周长最小的形状，也是最好读的形状。
 *
 * 顺序就只能老老实实按市值降序了（squarified 的前提：大的先排，行才方得起来）。
 * 早先为了让小票散在大票之间做过一次交错重排，那是配合对半切用的 —— 对半切把小票
 * 全挤到一角，交错能打散它；squarified 本身就会把小票摊在边上，不需要了。
 *
 * 树只有两层：根是一行行（沿较长的那条边排开），每一行是里面的票（沿另一条边排开）。
 * 层数浅不只是好看：每深一层就多一层嵌套的 LinearLayout，而 RemoteViews 的嵌套是有代价的。
 *
 * 渲染见 {@code MarketHeatWidgetProvider}：一个内部节点是一个 LinearLayout
 * （沿宽度排就横着、沿高度排就竖着），叶子是一片写着名字的 TextView 格子。
 */
public final class MarketTreemap {

    private MarketTreemap() {
    }

    /** 排版树上的一个节点：叶子是一片格子，内部节点是一行（或一列）容器 */
    public static final class Node {
        /** 叶子才有：这一格是哪只票 */
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
     * @return 排版树的根；没什么可摆时给 null
     */
    public static Node layout(List<CapRow> rows, float widthPx, float heightPx) {
        List<CapRow> usable = new ArrayList<>();
        if (rows != null) {
            for (CapRow row : rows) {
                if (row != null && row.totalCap > 0d) usable.add(row);
            }
        }
        if (usable.isEmpty() || widthPx <= 1f || heightPx <= 1f) return null;
        // squarified 的前提就是按市值降序：大的先排，每一行才方得起来
        return rows(byCap(usable), 1f, widthPx, heightPx);
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

    /**
     * 把这块矩形切成一行行：每行铺满一条边，剩下的矩形接着分行。
     *
     * 一行的厚度 = 这一行的面积 ÷ 铺满那条边的长度，所以各行之厚相加恰好是整块矩形；
     * 各行的 weight 按市值给，画的时候面积预算再按 weight 分下去 —— 面积仍然只由市值决定。
     */
    private static Node rows(List<CapRow> items, float weight, float wPx, float hPx) {
        boolean along = wPx >= hPx;       // 竖条（左右并排）还是横条（上下堆叠）
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
            // 只能朝一个方向排，换向得换一层 —— 见下面的 head / tail
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
        if (from >= items.size()) {
            return block(lines, caps, used, weight, along);
        }
        // 中途换向了：已经排好的这几行是一块，剩下的那块矩形自己再分行（方向自然反过来）
        Node head = block(lines, caps, used, (float) (used / total), along);
        Node tail = rows(items.subList(from, items.size()), (float) (1d - used / total), w, h);
        List<Node> kids = new ArrayList<>(2);
        kids.add(head);
        kids.add(tail);
        return new Node(null, kids, along, weight);
    }

    /** 排好的一串行做成一列（{@code along} 与行方向交叉）：weights 在这一块里归一 */
    private static Node block(List<List<CapRow>> lines, List<Double> caps, double used,
                              float weight, boolean along) {
        if (lines.size() == 1) return strip(lines.get(0), weight, !along);
        List<Node> kids = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            kids.add(strip(lines.get(i), (float) (caps.get(i) / used), !along));
        }
        return new Node(null, kids, along, weight);
    }

    /** 一行：里面的票沿这条边一字排开，weight 按市值给（在这一行内归一） */
    private static Node strip(List<CapRow> line, float weight, boolean along) {
        if (line.size() == 1) return new Node(line.get(0), null, false, weight);
        double total = sum(line, line.size());
        List<Node> kids = new ArrayList<>(line.size());
        for (CapRow row : line) {
            float w = total > 0d ? (float) (Math.max(0d, row.totalCap) / total) : 1f / line.size();
            kids.add(new Node(row, null, false, w));
        }
        return new Node(null, kids, along, weight);
    }

    /**
     * 这一行放几只：从 {@code from} 开始取几个前缀，哪个前缀里「最瘦的那个格子」长宽比最小就要哪个。
     *
     * @param total 整块矩形的市值和（条带厚度 = 两侧长度 × 这一行的市值占比）
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
                if (ext < 1f) {                          // 这一只已经瘦成一条线
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
