package com.tsymiar.device2device.game;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 声纹推演引擎
 * 管理命题、推演合成、三大心理陷阱（回声强化/负片覆盖/自主学习）
 * （对应 Qt 端 DeductionEngine.h/cpp）
 */
public class DeductionEngine {

    public interface Listener {
        void propositionResolved(int id);
        void memoryRewritten(String description);
        void echoReinforced(int sampleId);
    }

    private final List<DeductionData.DeductionSample> mSamples = new ArrayList<>();
    private final List<DeductionData.Proposition> mPropositions = new ArrayList<>();
    private final DeductionData.PsychProfile mProfile = new DeductionData.PsychProfile();
    private int mChapter = 0;
    private int mNextSampleId = 0;
    private int mNextPropositionId = 0;
    private final Map<Integer, Integer> mConvictCount = new HashMap<>();
    private final Map<Integer, String> mOverwrittenMemories = new HashMap<>();
    private Listener mListener;

    public DeductionEngine() {
    }

    public void setListener(Listener l) {
        mListener = l;
    }

    // ═══════════════════════════════════════════════════════════════
    //  样本库构建
    // ═══════════════════════════════════════════════════════════════

    private DeductionData.DeductionSample addSample(String name, String desc,
                                                    DeductionData.SampleKind kind, String source) {
        DeductionData.DeductionSample s = new DeductionData.DeductionSample();
        s.id = mNextSampleId++;
        s.name = name;
        s.description = desc;
        s.kind = kind;
        s.source = source;
        mSamples.add(s);
        return s;
    }

    private DeductionData.Proposition addProposition(String text, List<String> hints) {
        DeductionData.Proposition p = new DeductionData.Proposition();
        p.id = mNextPropositionId++;
        p.text = text;
        p.hints = hints;
        mPropositions.add(p);
        return p;
    }

    public List<DeductionData.DeductionSample> samples() {
        return mSamples;
    }

    public List<DeductionData.Proposition> propositions() {
        return mPropositions;
    }

    // ═══════════════════════════════════════════════════════════════
    //  序章：例行推演
    // ═══════════════════════════════════════════════════════════════

    public void setupPrologue() {
        mSamples.clear();
        mPropositions.clear();
        mChapter = 0;

        addSample("林薇的日常语调", "平淡、清晰，无情绪波动", DeductionData.SampleKind.Character, "林薇");
        addSample("林薇与陈远山最后一次对话", "背景中有异常的玻璃摩擦声", DeductionData.SampleKind.Character, "林薇/陈远山");
        addSample("林薇的书面便签", "「别推演我，推演你自己。」", DeductionData.SampleKind.Emotion, "林薇");

        addProposition("林薇在失踪前72小时的灵魂状态：她是否「蓄谋归灵」？",
                java.util.Arrays.asList("碎璃声灵放在'过去'是偶然杂音，放在'未来'预示背叛者逼近"));
    }

    // ═══════════════════════════════════════════════════════════════
    //  第一章：推演他人
    // ═══════════════════════════════════════════════════════════════

    public void setupChapter1() {
        mSamples.clear();
        mPropositions.clear();
        mChapter = 1;

        addSample("老刘的沉默呼吸", "规律、低沉，有轻微的停顿", DeductionData.SampleKind.Character, "老刘");
        addSample("门卫室铁门开合声", "沉重、缓慢，铰链有锈蚀声", DeductionData.SampleKind.Environment, "门卫室");
        addSample("何悦哼唱旋律", "《小星星》，干净无杂质", DeductionData.SampleKind.Character, "何悦");
        addSample("空调低频噪音", "持续的 50Hz 嗡鸣", DeductionData.SampleKind.Environment, "实验室空调");
        addSample("陈远山单侧听力测试报告", "右耳波形平坦，左耳正常", DeductionData.SampleKind.Character, "陈远山");
        addSample("陈远山关门声", "左侧总比右侧轻1分贝", DeductionData.SampleKind.Environment, "陈远山办公室");

        addProposition("林薇把「灵魂基频」藏在了哪里？",
                java.util.Arrays.asList("老刘是否知道核心盘位置？", "何悦哼的歌里是否藏有密钥负片？", "陈远山的右耳是否被声灵诅咒？"));
    }

    // ═══════════════════════════════════════════════════════════════
    //  第二章：推演自我
    // ═══════════════════════════════════════════════════════════════

    public void setupChapter2() {
        mSamples.clear();
        mPropositions.clear();
        mChapter = 2;

        addSample("刹车声", "尖锐刺耳的轮胎摩擦", DeductionData.SampleKind.Environment, "车祸现场");
        addSample("金属扭曲", "车身变形的沉闷巨响", DeductionData.SampleKind.Environment, "车祸现场");
        addSample("林薇的哭泣", "压抑、颤抖的抽泣", DeductionData.SampleKind.Emotion, "林薇");
        addSample("心电图停止音", "持续的'滴——'长鸣", DeductionData.SampleKind.Environment, "医院");
        addSample("手术器械声", "金属碰撞的清脆声", DeductionData.SampleKind.Environment, "手术室");
        addSample("林薇的指令声", "急促而专业", DeductionData.SampleKind.Character, "林薇");

        addProposition("我的「生命基频」是否被调换过？",
                java.util.Arrays.asList("心电图停止音与林薇哭泣的先后，决定她是救助者还是诱因", "仪器滴声的变速暗示割魂是否仓促"));
    }

    // ═══════════════════════════════════════════════════════════════
    //  第三章：推演关系（博弈模拟）
    // ═══════════════════════════════════════════════════════════════

    public void setupChapter3() {
        mSamples.clear();
        mPropositions.clear();
        mChapter = 3;

        addSample("陈远山的愤怒爆发", "声压极高，集中在左声道", DeductionData.SampleKind.Character, "陈远山");
        addSample("陈远山的平静陈述", "尾音微颤，呼吸不稳", DeductionData.SampleKind.Character, "陈远山");
        addSample("紧急按钮声", "机械按下的咔哒声", DeductionData.SampleKind.Environment, "实验室");
        addSample("林薇的挑衅语音（伪造）", "你在推演中伪造的样本", DeductionData.SampleKind.Emotion, "推演盘生成");

        addProposition("如果在最终对峙中，我对陈远山说X，他会怎么做？",
                java.util.Arrays.asList("镜声灵契约：预知他的第一反应呼吸频率", "默声灵契约：预知静默持续时长", "谎声灵契约：预知你最不愿面对的结局"));
    }

    // ═══════════════════════════════════════════════════════════════
    //  终章：推演未来
    // ═══════════════════════════════════════════════════════════════

    public void setupFinale() {
        mSamples.clear();
        mPropositions.clear();
        mChapter = 4;

        addSample("林薇的生命维持低频", "0.5Hz，微弱的生命信号", DeductionData.SampleKind.Emotion, "林薇");
        addSample("何悦的清洁声纹", "天然干净，无信息污染", DeductionData.SampleKind.Character, "何悦");
        addSample("你自己的日常呼吸声", "平静而规律", DeductionData.SampleKind.Character, "你自己");
        addSample("老刘的摩斯密码", "7下敲击，代表'GO'", DeductionData.SampleKind.Character, "老刘");
        addSample("走廊掌声", "二十多年前A-Lab成立典礼的掌声", DeductionData.SampleKind.Environment, "走廊");
        addSample("男孩日记的数字幻觉", "第114514只羊", DeductionData.SampleKind.Emotion, "第7号实验体");

        addProposition("我在声坛之上，要用哪三段声音作为供品？",
                java.util.Arrays.asList("第一供品：牺牲（释放一只声灵回归原初）", "第二供品：传递（把真相注入某人的梦）", "第三供品：定义（念出陈远山的灵魂基频）"));
    }

    // ═══════════════════════════════════════════════════════════════
    //  样本查找 / 锁存
    // ═══════════════════════════════════════════════════════════════

    public DeductionData.DeductionSample findSample(int id) {
        for (DeductionData.DeductionSample s : mSamples) {
            if (s.id == id) return s;
        }
        return null;
    }

    public void lockSample(int id) {
        DeductionData.DeductionSample s = findSample(id);
        if (s != null) s.locked = true;
    }

    public boolean isSampleLocked(int id) {
        for (DeductionData.DeductionSample s : mSamples) {
            if (s.id == id) return s.locked;
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════════
    //  命题
    // ═══════════════════════════════════════════════════════════════

    public DeductionData.Proposition currentProposition() {
        for (DeductionData.Proposition p : mPropositions) {
            if (!p.resolved) return p;
        }
        if (!mPropositions.isEmpty()) return mPropositions.get(mPropositions.size() - 1);
        return null;
    }

    public void resolveProposition(int id) {
        for (DeductionData.Proposition p : mPropositions) {
            if (p.id == id) {
                p.resolved = true;
                break;
            }
        }
        if (mListener != null) mListener.propositionResolved(id);
    }

    // ═══════════════════════════════════════════════════════════════
    //  推演核心：由样本+动作+锚点+关系合成推演结果
    // ═══════════════════════════════════════════════════════════════

    public DeductionData.DeductionResult deduce(List<DeductionData.DeductionSlot> boardSlots,
                                                List<DeductionData.Relation> relations) {
        DeductionData.DeductionResult r = new DeductionData.DeductionResult();
        if (boardSlots.size() < 2) {
            r.audioDescription = "需要至少两个声纹样本才能推演。";
            r.logicInference = "推演板拒绝空转。";
            r.credibility = 0.0f;
            return r;
        }

        // 组装推演音频描述（程序实时合成的文字描述，实际音频由 DeductionAudio 变调叠加）
        List<String> desc = new ArrayList<>();
        for (DeductionData.DeductionSlot slot : boardSlots) {
            DeductionData.DeductionSample s = findSample(slot.sampleId);
            if (s == null) continue;
            String action = slot.action.label();
            String anchor = slot.anchor.label();
            desc.add(s.name + "[" + action + "] 于" + anchor);
        }

        // 关系描述
        List<String> relDesc = new ArrayList<>();
        for (DeductionData.Relation rel : relations) {
            relDesc.add(rel.label());
        }

        r.audioDescription = "推演音频：" + String.join(" + ", desc);
        if (!relDesc.isEmpty()) r.audioDescription += "（关系：" + String.join("/", relDesc) + "）";

        // 心理可信度：受样本多样性、关系明确度、心理变量影响
        float baseCred = 0.4f + 0.1f * boardSlots.size();
        if (boardSlots.size() >= 3) baseCred += 0.1f;
        if (relations.size() >= 2) baseCred += 0.1f;
        // 贪婪/恐惧比例越极端，越可能是偏见回声
        float bias = Math.abs(mProfile.greed - mProfile.fear);
        baseCred -= bias * 0.2f;
        r.credibility = Math.max(0.0f, Math.min(1.0f, baseCred));

        // 逻辑推断
        boolean hasFutureAnchor = false;
        for (DeductionData.DeductionSlot s : boardSlots) {
            if (s.anchor == DeductionData.TimeAnchor.Future) hasFutureAnchor = true;
        }
        if (hasFutureAnchor) {
            r.logicInference = "推演显示：这段声纹关系指向未来——你在用未来解释过去。";
        } else {
            r.logicInference = "推演显示：所有样本锚定在过去，这是一次对既成事实的重构。";
        }

        // 记忆改写判定
        r.rewritesMemory = (r.credibility > 0.65f);
        if (r.rewritesMemory) {
            r.memoryEffects.add("推演结果正在改写你的记忆库。");
        }
        return r;
    }

    // ═══════════════════════════════════════════════════════════════
    //  心理变量
    // ═══════════════════════════════════════════════════════════════

    public DeductionData.PsychProfile profile() {
        return mProfile;
    }

    public void setGreedFear(float greed, float fear) {
        mProfile.greed = Math.max(0.0f, Math.min(1.0f, greed));
        mProfile.fear = Math.max(0.0f, Math.min(1.0f, fear));
    }

    public void recordAnchor(DeductionData.TimeAnchor a) {
        switch (a) {
            case Past:    mProfile.pastCount++; break;
            case Present: mProfile.presentCount++; break;
            case Future:  mProfile.futureCount++; break;
            default: break;
        }
    }

    public void recordEmotion(boolean fearPreferred) {
        if (fearPreferred) mProfile.fearEmotionCount++;
        else mProfile.nostalgiaCount++;
    }

    // ═══════════════════════════════════════════════════════════════
    //  心理陷阱 1：回声强化
    // ═══════════════════════════════════════════════════════════════

    public void applyEchoReinforcement(int sampleId, boolean convicted) {
        int count = mConvictCount.containsKey(sampleId) ? mConvictCount.get(sampleId) : 0;
        if (convicted) {
            count++;
        } else {
            count = Math.max(0, count - 1);
        }
        mConvictCount.put(sampleId, count);
        DeductionData.DeductionSample s = findSample(sampleId);
        if (s == null) return;
        if (count >= 3) {
            // 连续3次"有罪"→ 音高升高0.2八度（听起来更刺耳）
            s.basePitch = 1.0f + 0.2f * (float) Math.pow(2.0f, (count - 3) / 12.0f);
            if (mListener != null) mListener.echoReinforced(sampleId);
        } else {
            s.basePitch = 1.0f;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  心理陷阱 2：负片记忆覆盖
    // ═══════════════════════════════════════════════════════════════

    public List<String> negativeOverwrite(int sampleId, String newMemory) {
        List<String> effects = new ArrayList<>();
        DeductionData.DeductionSample s = findSample(sampleId);
        if (s == null) return effects;
        if (s.locked) {
            effects.add("样本「" + s.name + "」已锁存，未被覆盖。");
            return effects;
        }
        mOverwrittenMemories.put(sampleId, newMemory);
        s.description = newMemory;
        String effect = "样本「" + s.name + "」的记忆被推演结果覆盖：" + newMemory;
        effects.add(effect);
        if (mListener != null) mListener.memoryRewritten(effect);
        return effects;
    }

    // ═══════════════════════════════════════════════════════════════
    //  心理陷阱 3：自主学习（人格侧写）
    // ═══════════════════════════════════════════════════════════════

    public DeductionData.PersonalityProfile generatePersonalityProfile() {
        DeductionData.PersonalityProfile pp = new DeductionData.PersonalityProfile();
        pp.totalDeductions = mProfile.pastCount + mProfile.presentCount + mProfile.futureCount;

        // 偏好时间锚点
        int maxA = Math.max(Math.max(mProfile.pastCount, mProfile.presentCount), mProfile.futureCount);
        if (maxA == mProfile.futureCount && maxA > 0) pp.preferAnchor = "未来";
        else if (maxA == mProfile.presentCount && maxA > 0) pp.preferAnchor = "现在";
        else pp.preferAnchor = "过去";

        // 偏好情绪
        if (mProfile.fearEmotionCount >= mProfile.nostalgiaCount) pp.preferEmotion = "恐惧";
        else pp.preferEmotion = "怀旧";

        // 对陈远山的判断
        if (mProfile.greed > mProfile.fear) pp.verdict = "治愈他的耳朵";
        else pp.verdict = "永久静音他的世界";

        pp.summary = String.format(
                "你是一个偏好用「%1$s」%2$s「%3$s」的推演者。" +
                "你在第%4$d次推演中完成了最后一次因果重构。" +
                "推演板不会撒谎——它只是回声。",
                pp.preferAnchor,
                pp.preferAnchor.equals("未来") ? "逃避" : "审视",
                pp.preferEmotion,
                pp.totalDeductions);
        return pp;
    }

    // ═══════════════════════════════════════════════════════════════
    //  终章三槽推演
    // ═══════════════════════════════════════════════════════════════

    public String generateFinalAudioDescription(List<DeductionData.DeductionSlot> boardSlots) {
        if (boardSlots.size() < 3) return "三个空槽尚未填满。";
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < boardSlots.size() && i < 3; ++i) {
            DeductionData.DeductionSample s = findSample(boardSlots.get(i).sampleId);
            if (s == null) continue;
            String slotName = (i == 0) ? "牺牲" : (i == 1) ? "传递" : "定义";
            parts.add(String.format("第%d槽[%s]：%s", i + 1, slotName, s.name));
        }
        StringBuilder ret = new StringBuilder("终局音频（唯一）由你推演出：\n" + String.join("\n", parts));
        ret.append("\n你最终的决定是：").append(generatePersonalityProfile().verdict).append("。");
        return ret.toString();
    }
}
