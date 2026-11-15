package com.tsymiar.device2device.game;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 声纹推演引擎：命题、推演合成、三大心理陷阱（回声强化 / 负片覆盖 / 自主学习）。
 */
public class DeductionEngine {

    public interface Listener {
        void propositionResolved(int id);
        void memoryRewritten(String description);
        void echoReinforced(int sampleId);
        void spiritManifested(DeductionData.SpiritKind kind);
        void spiritBacklash(DeductionData.SpiritKind kind);
        void primeEchoOverloaded();
        void soulFragmentCollected(int id);
    }

    private final List<DeductionData.DeductionSample> mSamples = new ArrayList<>();
    private final List<DeductionData.Proposition> mPropositions = new ArrayList<>();
    private final DeductionData.PsychProfile mProfile = new DeductionData.PsychProfile();
    private int mChapter = 0;
    private int mNextSampleId = 0;
    private int mNextPropositionId = 0;
    private final Map<Integer, Integer> mConvictCount = new HashMap<>();
    private final Map<Integer, String> mOverwrittenMemories = new HashMap<>();

    private final List<DeductionData.SpiritInfo> mSpirits = new ArrayList<>();
    private final List<DeductionData.SoulFragment> mSoulFragments = new ArrayList<>();
    private DeductionData.DeviationEnergy mDeviation = new DeductionData.DeviationEnergy();
    private final Map<DeductionData.ContractSpirit, Boolean> mContracts = new HashMap<>();
    private Listener mListener;

    public DeductionEngine() {
        initSpirits();
        initSoulFragments();
    }

    public void setListener(Listener l) {
        mListener = l;
    }

    private DeductionData.DeductionSample addSample(String name, String desc,
                                                    DeductionData.SampleKind kind, String source,
                                                    DeductionData.SpiritKind spiritKind) {
        DeductionData.DeductionSample s = new DeductionData.DeductionSample();
        s.id = mNextSampleId++;
        s.name = name;
        s.description = desc;
        s.kind = kind;
        s.source = source;
        s.spiritKind = spiritKind;
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

    public void setupPrologue() {
        mSamples.clear();
        mPropositions.clear();
        mChapter = 0;

        addSample("林薇的日常语调", "平淡、清晰，无情绪波动", DeductionData.SampleKind.Character, "林薇", DeductionData.SpiritKind.Mirror);
        addSample("林薇与陈远山最后一次对话", "背景中有异常的玻璃摩擦声", DeductionData.SampleKind.Character, "林薇/陈远山", DeductionData.SpiritKind.Shattered);
        addSample("林薇的书面便签", "「别推演我，推演你自己。」", DeductionData.SampleKind.Emotion, "林薇", DeductionData.SpiritKind.PrimeEcho);

        addProposition("林薇在失踪前72小时的灵魂状态：她是否「蓄谋归灵」？",
                java.util.Arrays.asList("碎璃声灵放在'过去'是偶然杂音，放在'未来'预示背叛者逼近"));
    }

    public void setupChapter1() {
        mSamples.clear();
        mPropositions.clear();
        mChapter = 1;

        addSample("老刘的沉默呼吸", "规律、低沉，有轻微的停顿", DeductionData.SampleKind.Character, "老刘", DeductionData.SpiritKind.Silence);
        addSample("门卫室铁门开合声", "沉重、缓慢，铰链有锈蚀声", DeductionData.SampleKind.Environment, "门卫室", DeductionData.SpiritKind.Shattered);
        addSample("何悦哼唱旋律", "《小星星》，干净无杂质", DeductionData.SampleKind.Character, "何悦", DeductionData.SpiritKind.Mirror);
        addSample("空调低频噪音", "持续的 50Hz 嗡鸣", DeductionData.SampleKind.Environment, "实验室空调", DeductionData.SpiritKind.PrimeEcho);
        addSample("陈远山单侧听力测试报告", "右耳波形平坦，左耳正常", DeductionData.SampleKind.Character, "陈远山", DeductionData.SpiritKind.Silence);
        addSample("陈远山关门声", "左侧总比右侧轻1分贝", DeductionData.SampleKind.Environment, "陈远山办公室", DeductionData.SpiritKind.Wrath);

        addProposition("林薇把「灵魂基频」藏在了哪里？",
                java.util.Arrays.asList("老刘是否知道核心盘位置？", "何悦哼的歌里是否藏有密钥负片？", "陈远山的右耳是否被声灵诅咒？"));
    }

    public void setupChapter2() {
        mSamples.clear();
        mPropositions.clear();
        mChapter = 2;

        addSample("刹车声", "尖锐刺耳的轮胎摩擦", DeductionData.SampleKind.Environment, "车祸现场", DeductionData.SpiritKind.Wrath);
        addSample("金属扭曲", "车身变形的沉闷巨响", DeductionData.SampleKind.Environment, "车祸现场", DeductionData.SpiritKind.Wrath);
        addSample("林薇的哭泣", "压抑、颤抖的抽泣", DeductionData.SampleKind.Emotion, "林薇", DeductionData.SpiritKind.Mirror);
        addSample("心电图停止音", "持续的'滴——'长鸣", DeductionData.SampleKind.Environment, "医院", DeductionData.SpiritKind.Silence);
        addSample("手术器械声", "金属碰撞的清脆声", DeductionData.SampleKind.Environment, "手术室", DeductionData.SpiritKind.Shattered);
        addSample("林薇的指令声", "急促而专业", DeductionData.SampleKind.Character, "林薇", DeductionData.SpiritKind.Mirror);

        addProposition("我的「生命基频」是否被调换过？",
                java.util.Arrays.asList("心电图停止音与林薇哭泣的先后，决定她是救助者还是诱因", "仪器滴声的变速暗示割魂是否仓促"));
    }

    public void setupChapter3() {
        mSamples.clear();
        mPropositions.clear();
        mChapter = 3;

        addSample("陈远山的愤怒爆发", "声压极高，集中在左声道", DeductionData.SampleKind.Character, "陈远山", DeductionData.SpiritKind.Wrath);
        addSample("陈远山的平静陈述", "尾音微颤，呼吸不稳", DeductionData.SampleKind.Character, "陈远山", DeductionData.SpiritKind.Mirror);
        addSample("紧急按钮声", "机械按下的咔哒声", DeductionData.SampleKind.Environment, "实验室", DeductionData.SpiritKind.Shattered);
        addSample("林薇的挑衅语音（伪造）", "你在推演中伪造的样本", DeductionData.SampleKind.Emotion, "推演盘生成", DeductionData.SpiritKind.Lie);

        addProposition("如果在最终对峙中，我对陈远山说X，他会怎么做？",
                java.util.Arrays.asList("镜声灵契约：预知他的第一反应呼吸频率", "默声灵契约：预知静默持续时长", "谎声灵契约：预知你最不愿面对的结局"));
    }

    public void setupFinale() {
        mSamples.clear();
        mPropositions.clear();
        mChapter = 4;

        addSample("林薇的生命维持低频", "0.5Hz，微弱的生命信号", DeductionData.SampleKind.Emotion, "林薇", DeductionData.SpiritKind.PrimeEcho);
        addSample("何悦的清洁声纹", "天然干净，无信息污染", DeductionData.SampleKind.Character, "何悦", DeductionData.SpiritKind.Mirror);
        addSample("你自己的日常呼吸声", "平静而规律", DeductionData.SampleKind.Character, "你自己", DeductionData.SpiritKind.PrimeEcho);
        addSample("老刘的摩斯密码", "7下敲击，代表'GO'", DeductionData.SampleKind.Character, "老刘", DeductionData.SpiritKind.Silence);
        addSample("走廊掌声", "二十多年前A-Lab成立典礼的掌声", DeductionData.SampleKind.Environment, "走廊", DeductionData.SpiritKind.PrimeEcho);
        addSample("男孩日记的数字幻觉", "第114514只羊", DeductionData.SampleKind.Emotion, "第7号实验体", DeductionData.SpiritKind.Lie);

        addProposition("我在声坛之上，要用哪三段声音作为供品？",
                java.util.Arrays.asList("第一供品：牺牲（释放一只声灵回归原初）", "第二供品：传递（把真相注入某人的梦）", "第三供品：定义（念出陈远山的灵魂基频）"));
    }

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

    public DeductionData.DeductionResult deduce(List<DeductionData.DeductionSlot> boardSlots,
                                                List<DeductionData.Relation> relations) {
        DeductionData.DeductionResult r = new DeductionData.DeductionResult();
        if (boardSlots.size() < 2) {
            r.audioDescription = "需要至少两个声纹样本才能推演。";
            r.logicInference = "推演板拒绝空转。";
            r.credibility = 0.0f;
            return r;
        }

        // 每次推演即召唤对应声灵
        for (DeductionData.DeductionSlot slot : boardSlots) {
            DeductionData.DeductionSample s = findSample(slot.sampleId);
            if (s != null) summonSpirit(s.spiritKind);
        }

        // 音频描述文本（实际音频由 DeductionAudio 变调叠加）
        List<String> desc = new ArrayList<>();
        for (DeductionData.DeductionSlot slot : boardSlots) {
            DeductionData.DeductionSample s = findSample(slot.sampleId);
            if (s == null) continue;
            String action = slot.action.label();
            String anchor = slot.anchor.label();
            desc.add(s.name + "[" + action + "] 于" + anchor);
        }

        List<String> relDesc = new ArrayList<>();
        for (DeductionData.Relation rel : relations) {
            relDesc.add(rel.label());
        }

        r.audioDescription = "推演音频：" + String.join(" + ", desc);
        if (!relDesc.isEmpty()) r.audioDescription += "（关系：" + String.join("/", relDesc) + "）";

        // 心理可信度：样本越多、关系越明确越高；贪婪/恐惧越极端越像偏见回声
        float baseCred = 0.4f + 0.1f * boardSlots.size();
        if (boardSlots.size() >= 3) baseCred += 0.1f;
        if (relations.size() >= 2) baseCred += 0.1f;
        float bias = Math.abs(mProfile.greed - mProfile.fear);
        baseCred -= bias * 0.2f;
        r.credibility = Math.max(0.0f, Math.min(1.0f, baseCred));

        boolean hasFutureAnchor = false;
        for (DeductionData.DeductionSlot s : boardSlots) {
            if (s.anchor == DeductionData.TimeAnchor.Future) hasFutureAnchor = true;
        }
        if (hasFutureAnchor) {
            r.logicInference = "推演显示：这段声纹关系指向未来——你在用未来解释过去。";
        } else {
            r.logicInference = "推演显示：所有样本锚定在过去，这是一次对既成事实的重构。";
        }

        r.rewritesMemory = (r.credibility > 0.65f);
        if (r.rewritesMemory) {
            r.memoryEffects.add("推演结果正在改写你的记忆库。");
        }
        return r;
    }

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

    /** 陷阱 1：回声强化 */
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
            // 3 次"有罪"后音高 +0.2，此后每多一次再升半音
            s.basePitch = 1.0f + 0.2f * (float) Math.pow(2.0f, (count - 3) / 12.0f);
            if (mListener != null) mListener.echoReinforced(sampleId);
        } else {
            s.basePitch = 1.0f;
        }
    }

    /** 陷阱 2：负片记忆覆盖 */
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

    /** 陷阱 3：自主学习（人格侧写） */
    public DeductionData.PersonalityProfile generatePersonalityProfile() {
        DeductionData.PersonalityProfile pp = new DeductionData.PersonalityProfile();
        pp.totalDeductions = mProfile.pastCount + mProfile.presentCount + mProfile.futureCount;

        int maxA = Math.max(Math.max(mProfile.pastCount, mProfile.presentCount), mProfile.futureCount);
        if (maxA == mProfile.futureCount && maxA > 0) pp.preferAnchor = "未来";
        else if (maxA == mProfile.presentCount && maxA > 0) pp.preferAnchor = "现在";
        else pp.preferAnchor = "过去";

        if (mProfile.fearEmotionCount >= mProfile.nostalgiaCount) pp.preferEmotion = "恐惧";
        else pp.preferEmotion = "怀旧";

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

    private void initSpirits() {
        mSpirits.clear();
        mSpirits.addAll(DeductionData.defaultSpirits());
    }

    private void initSoulFragments() {
        DeductionData.resetSoulFragments(mSoulFragments);
    }

    /** 新局重置：清每局状态，保留跨局剧情记忆 */
    public void resetRun() {
        mConvictCount.clear();
        mOverwrittenMemories.clear();
        mProfile.greed = 0.5f;
        mProfile.fear = 0.5f;
        mProfile.pastCount = 0;
        mProfile.presentCount = 0;
        mProfile.futureCount = 0;
        mProfile.fearEmotionCount = 0;
        mProfile.nostalgiaCount = 0;
        mProfile.spareCount = 0;
        mProfile.convictCount = 0;
        initSpirits();
        initSoulFragments();
        mDeviation = new DeductionData.DeviationEnergy();
        mContracts.clear();
    }

    public List<DeductionData.SpiritInfo> spirits() {
        return mSpirits;
    }

    public DeductionData.SpiritInfo findSpirit(DeductionData.SpiritKind kind) {
        for (DeductionData.SpiritInfo s : mSpirits) {
            if (s.kind == kind) return s;
        }
        return null;
    }

    /** 召唤声灵：好感度 +0.08/次，0.5 显形，0.85 反噬 */
    public void summonSpirit(DeductionData.SpiritKind kind) {
        DeductionData.SpiritInfo s = findSpirit(kind);
        if (s == null) return;
        s.affinity = Math.min(1.0f, s.affinity + 0.08f);
        if (s.affinity >= 0.5f && !s.manifested) {
            s.manifested = true;
            if (mListener != null) mListener.spiritManifested(kind);
        }
        if (s.affinity >= 0.85f && mListener != null) {
            mListener.spiritBacklash(kind);
        }
    }

    public float spiritAffinity(DeductionData.SpiritKind kind) {
        DeductionData.SpiritInfo s = findSpirit(kind);
        return s != null ? s.affinity : 0.0f;
    }

    public boolean isSpiritBacklashing(DeductionData.SpiritKind kind) {
        return spiritAffinity(kind) >= 0.85f;
    }

    public String spiritBacklashText(DeductionData.SpiritKind kind) {
        switch (kind) {
            case Lie:
                return "谎声灵开始主动润色你的推演——结果越来越合你心意，但你已分不清真假。";
            case Wrath:
                return "愤怒声灵让你的推演染上血红色——它正把你推向它想要的结局。";
            case Mirror:
                return "镜声灵映照出你不敢面对的真相——它逼你直视自己。";
            case Silence:
                return "默声灵吞掉了推演中的杂音——你发现自己的某段记忆被它吃掉了。";
            case Shattered:
                return "碎璃声灵在推演中低语——它说，背叛者早已站在你身后。";
            default:
                return "";
        }
    }

    public void accumulateDeviation(float delta) {
        mDeviation.amount = Math.min(1.0f, mDeviation.amount + delta);
        if (mDeviation.amount >= 1.0f && !mDeviation.overloaded) {
            mDeviation.overloaded = true;
            if (mListener != null) mListener.primeEchoOverloaded();
        }
    }

    public DeductionData.DeviationEnergy deviation() {
        return mDeviation;
    }

    public boolean isPrimeEchoOverloaded() {
        return mDeviation.overloaded;
    }

    public List<DeductionData.SoulFragment> soulFragments() {
        return mSoulFragments;
    }

    public void collectSoulFragment(int id) {
        if (id < 0 || id >= mSoulFragments.size()) return;
        if (!mSoulFragments.get(id).collected) {
            mSoulFragments.get(id).collected = true;
            mSoulFragments.get(id).glowing = true;
            if (mListener != null) mListener.soulFragmentCollected(id);
        }
    }

    public int collectedSoulFragmentCount() {
        int c = 0;
        for (DeductionData.SoulFragment f : mSoulFragments) {
            if (f.collected) c++;
        }
        return c;
    }

    public boolean allSoulFragmentsCollected() {
        return collectedSoulFragmentCount() == mSoulFragments.size();
    }

    public void makeContract(DeductionData.ContractSpirit spirit) {
        mContracts.put(spirit, true);
        // 缔结契约 = 割舍灵魂 → 收集一块碎片
        int idx = mContracts.size() - 1;
        if (idx >= 0 && idx < mSoulFragments.size()) {
            collectSoulFragment(idx);
        }
    }

    public boolean hasContract(DeductionData.ContractSpirit spirit) {
        Boolean v = mContracts.get(spirit);
        return v != null && v;
    }

    public String contractCostText(DeductionData.ContractSpirit spirit) {
        switch (spirit) {
            case Mirror:
                return "镜声灵契约：你必须大声说出自己最恐惧的一句真话。";
            case Silence:
                return "默声灵契约：你必须删除一段自己最喜欢的记忆。";
            case Lie:
                return "谎声灵契约：你必须当着声灵面撒一个会被它识破的谎。";
            default:
                return "";
        }
    }

    public String releaseSpirit(DeductionData.SpiritKind kind) {
        DeductionData.SpiritInfo s = findSpirit(kind);
        if (s == null) return "";
        String name = s.name;
        s.manifested = false;
        s.affinity = 0.0f;
        mDeviation.releaseCount++;
        accumulateDeviation(0.1f);
        int idx = mDeviation.releaseCount - 1;
        if (idx >= 0 && idx < mSoulFragments.size()) {
            collectSoulFragment(idx);
        }
        return "你释放了「" + name + "」，它回归了原初嗡鸣。";
    }
}
