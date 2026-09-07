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
        // 偏好的时间锚点统计
        public int pastCount = 0;
        public int presentCount = 0;
        public int futureCount = 0;
        // 偏好的情绪变量
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
}
