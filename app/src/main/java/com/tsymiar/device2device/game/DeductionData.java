package com.tsymiar.device2device.game;

import java.util.ArrayList;
import java.util.List;

/**
 * 声纹推演盘 —— 核心数据结构
 * 推演取代回溯：由玩家的因果推断主动触发剧情
 * （对应 Qt 端 DeductionData.h）
 */
public final class DeductionData {

    // ── 三要素之一：声纹样本（人物/环境/情感印记）──
    public enum SampleKind {
        Character,   // 人物声纹
        Environment, // 环境声
        Emotion      // 情感印记
    }

    public static class DeductionSample {
        public int id = -1;
        public String name = "";          // 样本名
        public String description = "";   // 描述
        public SampleKind kind = SampleKind.Character;
        public String source = "";        // 来源人物/地点
        public float basePitch = 1.0f;    // 基础音高（1.0 = 原始）
        public boolean locked = false;    // 是否已锁存（防止负片记忆覆盖）
        public int suspicionWeight = 0;   // 被推演为"有罪"的累计次数（回声强化）
        public SpiritKind spiritKind = SpiritKind.PrimeEcho; // 关联的声灵（离奇魔幻基因）

        public int color() {
            switch (kind) {
                case Character:   return 0xFF00B4FF; // 0,180,255
                case Environment: return 0xFF00DCA0;
                case Emotion:     return 0xFFFF964F;
            }
            return 0xFFFFFFFF;
        }

        public String kindLabel() {
            switch (kind) {
                case Character:   return "人物";
                case Environment: return "环境";
                case Emotion:     return "情感";
            }
            return "";
        }
    }

    // ── 声灵类型（离奇魔幻基因）──
    public enum SpiritKind {
        PrimeEcho,    // 原初嗡鸣（源头）
        Mirror,       // 镜声灵（反射）
        Silence,      // 默声灵（消除）
        Lie,          // 谎声灵（伪造）
        Shattered,    // 碎璃声灵（背叛）
        Wrath,        // 愤怒声灵
        Count
    }

    // ── 三要素之二：干预动作 ──
    public enum Intervention {
        Play,     // 播放
        Trim,     // 删减
        Pitch,    // 变调
        Forge;    // 伪造

        public static final int COUNT = 4;

        public String label() {
            switch (this) {
                case Play:  return "播放";
                case Trim:  return "删减";
                case Pitch: return "变调";
                case Forge: return "伪造";
            }
            return "播放";
        }
    }

    // ── 三要素之三：时间锚点 ──
    public enum TimeAnchor {
        Past,      // 过去
        Present,   // 现在
        Future;    // 未来

        public static final int COUNT = 3;

        public String label() {
            switch (this) {
                case Past:    return "过去";
                case Present: return "现在";
                case Future:  return "未来";
            }
            return "过去";
        }
    }

    // ── 推演盘三槽 ──
    public static class DeductionSlot {
        public int sampleId = -1;                          // 声纹样本 ID
        public Intervention action = Intervention.Play;    // 干预动作
        public TimeAnchor anchor = TimeAnchor.Past;        // 时间锚点

        public void clear() {
            sampleId = -1;
            action = Intervention.Play;
            anchor = TimeAnchor.Past;
        }
    }

    // ── 相对关系（掩盖/激发/反转/同步）──
    public enum Relation {
        Mask,      // 掩盖
        Excite,    // 激发
        Reverse,   // 反转
        Sync;      // 同步

        public static final int COUNT = 4;

        public String label() {
            switch (this) {
                case Mask:    return "掩盖";
                case Excite:  return "激发";
                case Reverse: return "反转";
                case Sync:    return "同步";
            }
            return "";
        }
    }

    // ── 待验证命题 ──
    public static class Proposition {
        public int id = -1;
        public String text = "";              // 命题文本
        public List<String> hints = new ArrayList<>();  // 提示
        public boolean resolved = false;      // 是否已推演
        // 心理可信度（0.0 = 纯粹偏见回声，1.0 = 真相的影子）
        public float psychologicalCredibility = 0.0f;
    }

    // ── 推演结果 ──
    public static class DeductionResult {
        public String audioDescription = "";  // 推演音频的文字描述（视觉降级时用）
        public String logicInference = "";    // 逻辑推断结论
        public float credibility = 0.5f;      // 心理可信度
        public boolean rewritesMemory = false; // 是否改写记忆库
        public List<String> memoryEffects = new ArrayList<>(); // 记忆改写效果描述
    }

    // ── 心理变量（贪婪/恐惧比例等）──
    public static class PsychProfile {
        public float greed = 0.5f;        // 贪婪 0.0-1.0
        public float fear = 0.5f;         // 恐惧 0.0-1.0
        public int pastCount = 0;
        public int presentCount = 0;
        public int futureCount = 0;
        public int fearEmotionCount = 0;
        public int nostalgiaCount = 0;
        // 放过/定罪统计
        public int spareCount = 0;
        public int convictCount = 0;
    }

    // ── 推演盘自主学习的人格侧写 ──
    public static class PersonalityProfile {
        public String preferAnchor = "";   // 偏好时间锚点
        public String preferEmotion = "";  // 偏好情绪
        public String verdict = "";        // 对陈远山的最终判断
        public int totalDeductions = 0;
        public String summary = "";        // 心理侧写报告文本
    }

    // ═══════════════════════════════════════════════════════════════
    //  离奇魔幻基因：声灵系统（与 Qt 端 DeductionData.h 同源，台词逐字一致）
    // ═══════════════════════════════════════════════════════════════

    // ── 声灵结构（有自我意识、立场、好感度）──
    public static class SpiritInfo {
        public SpiritKind kind = SpiritKind.PrimeEcho;
        public String name = "";            // 声灵名
        public String title = "";           // 称号
        public String stance = "";          // 立场/欲望
        public float affinity = 0.0f;       // 好感度 0.0-1.0（越召唤越高）
        public boolean manifested = false;  // 是否已显形（光晕）

        public int color() {
            switch (kind) {
                case PrimeEcho:  return 0xFFFFDC78; // 金色
                case Mirror:     return 0xFF96C8FF; // 淡蓝
                case Silence:    return 0xFFC8B4FF; // 淡紫
                case Lie:        return 0xFFFF8CB4; // 粉红
                case Shattered:  return 0xFF78E6B4; // 青绿
                case Wrath:      return 0xFFFF5A5A; // 血红
            }
            return 0xFFFFFFFF;
        }
    }

    // 声灵库默认数据，由推演盘和回响引擎共用，避免两处各存一份副本
    public static List<SpiritInfo> defaultSpirits() {
        Object[][] defs = {
                { SpiritKind.PrimeEcho, "原初嗡鸣", "万声之母", "它是起源，也是审判者。" },
                { SpiritKind.Mirror,    "镜声灵",   "反射之灵", "它渴求真相，哪怕真相刺穿你。" },
                { SpiritKind.Silence,   "默声灵",   "消除之灵", "它渴望寂静，代价是你的记忆。" },
                { SpiritKind.Lie,       "谎声灵",   "伪造之灵", "它以谎言为食，也会用谎言喂养你。" },
                { SpiritKind.Shattered, "碎璃声灵", "背叛之灵", "它附身于背叛者的脚步声。" },
                { SpiritKind.Wrath,     "愤怒声灵", "暴怒之灵", "它煽动你的怒火，再从中取暖。" },
        };
        List<SpiritInfo> list = new ArrayList<>();
        for (Object[] d : defs) {
            SpiritInfo s = new SpiritInfo();
            s.kind = (SpiritKind) d[0];
            s.name = (String) d[1];
            s.title = (String) d[2];
            s.stance = (String) d[3];
            list.add(s);
        }
        return list;
    }

    // ── 灵魂碎片（割舍声音后收集，拼成林薇母亲的脸）──
    public static class SoulFragment {
        public int id = -1;
        public String name = "";        // 碎片名
        public String source = "";      // 来源（割舍的声音/释放的声灵）
        public boolean collected = false;
        public boolean glowing = false; // 泛光状态
    }

    // 六块碎片拼成林薇母亲的脸，id 从 0 连续编号，收集逻辑按下标索引
    public static void resetSoulFragments(List<SoulFragment> fragments) {
        fragments.clear();
        String[] names = { "眉骨", "左眼", "右眼", "鼻梁", "唇角", "下颌" };
        for (int i = 0; i < names.length; ++i) {
            SoulFragment f = new SoulFragment();
            f.id = i;
            f.name = names[i];
            f.source = "割舍的声音";
            fragments.add(f);
        }
    }

    // ── 契约（与声灵缔结）──
    public enum ContractSpirit {
        None,
        Mirror,   // 镜声灵契约：说出最恐惧的真话
        Silence,  // 默声灵契约：删除最喜欢的记忆
        Lie       // 谎声灵契约：撒一个会被识破的谎
    }

    // ── 偏差能量（原初嗡鸣倒计时）──
    public static class DeviationEnergy {
        public float amount = 0.0f;        // 偏差能量 0.0-1.0
        public boolean overloaded = false; // 是否触发嗡鸣
        public int releaseCount = 0;       // 释放声灵次数（触发逆向推演）
    }
}
