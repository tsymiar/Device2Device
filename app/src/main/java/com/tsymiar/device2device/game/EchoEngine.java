package com.tsymiar.device2device.game;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 《余音回响 Echo Resonance》游戏引擎
 *
 * 同步自 QtGames/echo_resonance（完整剧情版）：
 *  - 声纹实验室悬疑故事：周宁 / 林薇 / 陈远山 / 老刘 / 底噪
 *  - 锚点音系统：每章首个槽位需要指定的锚点音（门禁刷卡 / 咖啡机 / 心率监护仪 / 钥匙 / 钟声）
 *  - 逆向播放：第一章后解锁，轮胎摩擦与心跳倒放隐藏秘密
 *  - 底噪意识系统：放置错误碎片增强低频，>0.5 底噪对话，>0.85 静默模式
 *  - 摩斯电码：老刘的桌面敲击（序章起激活）
 *  - 幻觉系统：偏执 >0.7 时概率触发
 *  - 选择分支：序章 / 第三章 / 第四章
 *  - 六结局：静默档案 / 回声孤儿 / 双声部 / 弑母 / 倒带者 / 零分贝
 */
public class EchoEngine {

    /** 碎片可信度 */
    public enum Credibility {
        REAL(0xFF4FC3F7, "真实残留"),      // 蓝
        SUSPICIOUS(0xFF26C6DA, "可疑"),    // 青
        NOISE(0xFFFFD54F, "噪声"),         // 黄
        FAKE(0xFFEF5350, "伪造");          // 红

        public final int color;
        public final String label;

        Credibility(int color, String label) {
            this.color = color;
            this.label = label;
        }
    }

    /** 游戏阶段（同步 Qt：Title→ChapterIntro→Tutorial(序章)→FragmentSelect↔Timeline→SceneComplete→(Mentor|Choice)→Ending→GameOver） */
    public enum State {
        TITLE,          // 标题
        NAME_INPUT,     // 角色名输入（Qt：NameInput）
        CHAPTER_INTRO,  // 章节引言
        TUTORIAL,       // 序章教程页
        FRAGMENT_SELECT,// 选择碎片
        TIMELINE,       // 时间轴拼图
        SCENE_COMPLETE, // 场景完成（一致性结果）
        MENTOR,         // 导师语音日志
        CHOICE,         // 选择分支
        ENDING,         // 结局
        EPILOGUE,       // 结局余波录音（Qt EpilogueScreen）
        ECHO_ARCHIVE,   // 回声碎片档案（Qt EchoArchiveScreen，碎片选择页按 E 进入）
        GAME_OVER       // 二周目（结局后 ENTER 返回标题画面）
    }

    /** 玩家选择 */
    public enum PlayerChoice {
        NONE,
        IGNORE_SIGH, RECORD_SIGH,          // 序章
        ACCEPT_FUSION, REJECT_FUSION, SEPARATE_VOICES, // 第三章
        TRANSFER_BACK, DELETE_LIFE_SUPPORT // 第四章
    }

    /** Qt：何悦分支状态（CommonFragment.h: HeYueState） */
    public enum HeYueState {
        UNKNOWN,      // 未触发（Qt Unknown）
        RESCUED,      // 成功救出（绝对参考系"照妖镜"）
        BRAINWASHED   // 被洗脑成傀儡
    }

    /** 锚点音类型 */
    public enum AnchorType {
        NONE, DOOR_BEEP, COFFEE_MACHINE, HEART_MONITOR, KEY_TURN, CLOCK_CHIME
    }

    /** 碎片类型 */
    public enum FragmentType {
        FOOTSTEP("脚步声", "走廊里渐近的脚步声"),
        ELECTRIC_BUZZ("电流声", "设备电流嗡鸣"),
        WATER_DROP("滴水声", "水管深处滴水"),
        HEARTBEAT("心跳声", "低沉不规律的心跳"),
        VOICE_WHISPER("低语声", "无法辨认的低语"),
        STATIC_NOISE("白噪声", "持续背景噪声"),
        DOOR_CREAK("门吱呀声", "沉重铁门推开"),
        TYPEWRITER("打字机声", "老式打字机敲击"),
        BREATH("呼吸声", "缓慢沉重的呼吸"),
        LOW_FREQUENCY("低频嗡鸣", "超低频振动"),
        METAL_EXPANSION("金属膨胀", "金属热胀冷缩"),
        GEOMAGNETIC("地磁波动", "微弱地磁波动"),
        CLOCK_TICK("时钟滴答", "规律滴答声"),
        TIRE_SCREECH("轮胎摩擦", "刺耳的急刹声"),
        RAIN_AMBIENT("雨声", "持续的雨声"),
        // —— 扩展类型：扩充池容量至 28，保证各章碎片池内类型不重复 ——
        POWER_HUM("电源嗡鸣", "电源变压器的持续嗡鸣"),
        KEYBOARD_TYPING("键盘敲击", "急促的键盘敲击声"),
        PHONE_RING("电话铃声", "老式电话的响铃"),
        ELEVATOR_RUN("电梯运行", "电梯轿厢运行的闷响"),
        SIREN_FAR("远处警笛", "远处隐约的警笛声"),
        GLASS_BREAK("玻璃碎裂", "玻璃碎裂的脆响"),
        THUNDER_RUMBLE("闷雷声", "远方沉闷的雷声"),
        PAPER_RUSTLE("纸张翻动", "纸张翻动的沙沙声"),
        CHAIR_SCRAPE("椅子拖动", "椅子被拖动的刮擦声"),
        FAN_SPIN("风扇转动", "旧风扇叶片的转动声"),
        MOTOR_HUM("电机运转", "电机持续运转的低鸣"),
        PIPE_KNOCK("管道敲击", "水管被敲击的闷响"),
        RUNNING_STEP("奔跑脚步", "急促奔跑的脚步声");

        public final String name;
        public final String desc;

        FragmentType(String name, String desc) {
            this.name = name;
            this.desc = desc;
        }
    }

    /** 声音碎片 */
    public static class Fragment {
        public final int id;
        public final FragmentType type;
        public final String name;
        public final Credibility cred;
        public final boolean anchor;
        public final AnchorType anchorType;
        public final String desc;
        public boolean reversed;       // 是否已倒放
        public boolean revealsSecret; // 倒放隐藏秘密
        public String reverseDesc;
        public boolean isPlaced;
        public int timelineSlot = -1;

        Fragment(int id, FragmentType type, String name, Credibility cred, boolean anchor,
                 AnchorType anchorType, String desc) {
            this.id = id;
            this.type = type;
            this.name = name;
            this.cred = cred;
            this.anchor = anchor;
            this.anchorType = anchorType;
            this.desc = desc;
        }
    }

    /** 时间轴槽位 */
    public static class Slot {
        public final String hint;
        public final boolean anchorSlot;
        public final AnchorType requiredAnchor;
        public String placedId;

        Slot(String hint, boolean anchorSlot, AnchorType requiredAnchor) {
            this.hint = hint;
            this.anchorSlot = anchorSlot;
            this.requiredAnchor = requiredAnchor;
        }

        public boolean isFilled() {
            return placedId != null;
        }
    }

    /** 章节 */
    public static class Chapter {
        public final String title;          // 章节名
        public final String description;    // 章节剧情描述
        public final String sceneDesc;      // 场景完成描述
        public final String mentor;         // 导师语音日志
        public final PlayerChoice choiceAt; // 该章节结束后的选择点（NONE 表示无）
        public final AnchorType anchor;     // 本章锚点音
        public final List<Fragment> pool = new ArrayList<>();
        public final List<Slot> slots = new ArrayList<>();

        Chapter(String title, String description, String sceneDesc,
                String mentor, PlayerChoice choiceAt, AnchorType anchor) {
            this.title = title;
            this.description = description;
            this.sceneDesc = sceneDesc;
            this.mentor = mentor;
            this.choiceAt = choiceAt;
            this.anchor = anchor;
        }
    }

    /** 选择分支 */
    public static class Choice {
        public final String prompt;
        public final List<String> options;

        public Choice(String prompt, List<String> options) {
            this.prompt = prompt;
            this.options = options;
        }
    }

    /** 结局 */
    public static class Ending {
        public final String title;
        public final String text;
        public final String condition;
        public final int color;

        public Ending(String title, String text, String condition, int color) {
            this.title = title;
            this.text = text;
            this.condition = condition;
            this.color = color;
        }
    }

    /** Qt：角色档案（周宁/林薇/陈远山/老刘/底噪） */
    public static class Character {
        public final String name;
        public final String title;
        public final String secret;
        public boolean isRevealed;

        Character(String name, String title, String secret) {
            this.name = name;
            this.title = title;
            this.secret = secret;
        }
    }

    /** Qt：玩家角色名的统一默认值与占位符（CommonFragment.h: DEFAULT_PLAYER_NAME / PLAYER_NAME_PLACEHOLDER） */
    public static final String DEFAULT_PLAYER_NAME = "小周";
    public static final String PLAYER_NAME_PLACEHOLDER = "{name}";

    // ---------- 状态 ----------
    private final List<Chapter> mChapters = new ArrayList<>();    private final List<PlayerChoice> mChoices = new ArrayList<>();
    private final List<String> mNoiseDialogues = new ArrayList<>();
    private final List<String> mHallucinations = new ArrayList<>();
    private final List<String> mMorseMessages = new ArrayList<>();
    private final List<String> mMentorMessages = new ArrayList<>();
    private final List<Character> mCharacters = new ArrayList<>();
    private final Map<String, List<String>> mCharacterDialogues = new HashMap<>();
    private final Map<String, Boolean> mEndingUnlocked = new HashMap<>(); // Qt：结局解锁记录
    private final Map<String, String> mEpilogues = new HashMap<>(); // Qt：结局余波录音（按结局标题索引）
    private final List<EchoFragmentRecord> mEchoFragments = new ArrayList<>(); // Qt：回声碎片档案（6）
    private final List<ForgottenEntry> mForgottenEntries = new ArrayList<>(); // Qt：被遗忘者名单（6）
    private String mPlayerName = ""; // Qt：玩家名（导师消息 {name} 替换）
    private final Random mRandom = new Random();
    private int mNextId = 0;
    private int mChapterIndex = 0;
    private State mState = State.TITLE;
    private float mParanoia = 0f;
    private float mAnxiety = 0f;
    private float mLowFreq = 0f;
    private float mCoherence = 0f;
    private boolean mIsDistorted = false; // Qt：场景重建是否扭曲（coherence < 0.4）
    // ---- 声灵（Qt initSpirits / initSoulFragments / 偏差能量 / 静音惩罚，离奇魔幻基因）----
    private final java.util.ArrayList<SpiritInfo> mSpirits = new java.util.ArrayList<>();
    private final java.util.ArrayList<SoulFragment> mSoulFragments = new java.util.ArrayList<>();
    private float mDeviation = 0f;              // Qt：m_deviation 偏差能量
    private boolean mDeviationOverloaded = false;
    private int mSpiritReleaseCount = 0;        // Qt：m_deviation.releaseCount
    private float mSilenceTendency = 0f;        // Qt：静音倾向（放置伪造碎片累积）
    private boolean mRightEarSilenced = false;  // Qt：右耳被静音（底噪意识语气破碎）
    private final java.util.ArrayList<String> mSpiritLog = new java.util.ArrayList<>(); // 本局声灵回响（章节结算展示）
    private boolean mReverseUnlocked = false;
    private boolean mMorseActive = true;
    private boolean mSilenceTriggered = false;
    private boolean mHardMode = false;
    private boolean mUseOfficialAnchor = true; // Qt：默认使用官方锚点（参与静默档案结局判定）
    private HeYueState mHeYueState = HeYueState.UNKNOWN; // Qt：何悦状态
    private boolean mHeYuePending = false; // Qt：第三章完成 → 何悦抉择待决（随后才进身份认同）
    private int mCurrentFragmentIndex = 0;
    private String mPendingNoise;
    private String mPendingHallucination;
    private String mPendingMorse;

    public EchoEngine() {
        initCharacters();
        initDialogues();
        initEpilogues();
        initEchoFragments();
        initSpirits();
        initSoulFragments();
        buildChapters();
    }

    // ---------- 文本初始化 ----------

    /** Qt：initCharacters() —— 角色档案与对白表（对齐 Qt EchoEngine.cpp:28-78） */
    private void initCharacters() {
        mCharacters.add(new Character("{name}", "28岁，实验室助理研究员",
                "14岁时在一场车祸中被困变形车厢，林薇录下你敲击车门的呼救声精准定位救了你。"
                        + "你始终以为她是恩师，其实她把你当作'失散多年的回声'——你敲击的节奏与她母亲临终前心跳仪波形完全一致。"));
        mCharacters.add(new Character("林薇", "45岁，神经声学权威，失踪者",
                "发现军方要利用她的技术抹除'不稳定士兵'的人格，于是自行销毁核心数据。"
                        + "但她将'自己的完整人格声纹'移植给了{name}，作为逃逸载体。她救过你两次：一次在车祸现场，一次用她的生命。"));
        mCharacters.add(new Character("陈远山", "52岁，实验室负责人，军方背景，右耳先天失聪",
                "他年轻时是林薇最默契的搭档，但右耳完全失聪。他不恨林薇，他恨的是'声音有选择权'。"
                        + "当年他主张军事化开发，是因为军方答应治好他的耳朵，代价是交出所有成果。"
                        + "林薇的拒绝，在他眼中不是道德坚守，而是断了他唯一的希望。"));
        mCharacters.add(new Character("老刘", "62岁，门卫，沉默寡言",
                "林薇最早的实验助手，参与过第一次'人格声纹移植'——被试者是他的亲弟弟。"
                        + "移植失败后弟弟人格分裂，深夜砸碎所有录音设备后跳楼。老刘从此不再说话，自愿降职为门卫，"
                        + "终身守护地下档案库入口——不是为了保护秘密，是为了不让任何人再打开那扇门。"));
        mCharacters.add(new Character("何悦", "22岁，实习生，你的名义带教对象",
                "刚入职两周，天真话多，喜欢在工位哼歌。她的声音天然干净，不带任何信息污染，是唯一可以当作'绝对参考系'的音源。"
                        + "陈远山故意安排她坐你隔壁，用她的碎碎念掩盖异常低频——但她无意中录下了陈远山打给军方的加密电话。"));
        mCharacters.add(new Character("底噪", "存在于所有音频文件背景中的0.5Hz低频",
                "这是林薇提前植入的'唤醒信号'。当玩家拼出足够多的'错误版本'时，底噪会逐渐增强，最终与你直接对话。"));

        mCharacterDialogues.put("林薇", Arrays.asList(
                "核心盘我藏好了，你没机会的。",
                "{name}，如果你听到这段话，说明我已经不在了。",
                "你现在拼接的每一个声音，都来自你的未来。",
                "你是在给过去的我传递信息。所以，请告诉我：你那边，天亮了吗？",
                "对不起，我只能让你活在我的声音里。",
                "你不是被夺舍的容器，你是我的选择。",
                "孩子别怕，阿姨听得到你，阿姨这辈子都听得到你。",  // 14年前救援录音
                "欢迎回家。这里所有墙壁里都藏着我为你准备的备用碎片。如果有一天你连自己都不敢信了，就对着镜子说话，我会在镜子的另一面回答你。"));  // 镜中留言
        mCharacterDialogues.put("陈远山", Arrays.asList(
                "你藏在一段声音里对吧？那我毁掉所有声音。",
                "{name}，我看你门禁卡刷到了地下三层。那里辐射超标，快上来。",
                "你走到哪里，我就把哪里的音频切断。你是一只泡在静水里的耳朵——没有声音，你就是瞎子。",
                "薇薇，你知道右耳永远安静是什么感觉吗？那不是沉默，那是一个不断提醒你残缺的黑洞。你拒绝军用，等于把我重新推进那个黑洞里。",  // 地下三层隐藏录音
                "开枪吧。反正我这辈子，只听过一半的声音。另一半，是你妈留给我的谎言。"));  // 终章自我认知
        mCharacterDialogues.put("老刘", Arrays.asList(
                "",  // 老刘不说话，用摩斯电码
                "替我……给她的灵魂带句话。说'门，可以关了。'",  // 终章开口
                "谢谢你还活着。"));  // 回声孤儿结局的唇语
        mCharacterDialogues.put("何悦", Arrays.asList(
                "{name}老师，我昨天哼的那首歌你听出来了吗？是《小星星》哦，因为我的声音干净得只能唱这种歌啦。",
                "这个实验室好安静，安静得我有点害怕，所以我才一直唱歌……",
                "{name}老师……救我……他们在门外……",  // 被囚禁时的求救
                "我的声音很干净，对吧？你可以拿去当'参考系'用。这大概是我唯一能帮上忙的地方了。"));  // 借出清洁声纹
        mCharacterDialogues.put("底噪", Arrays.asList(
                "……你听到了吗？那不是我发出的声音，是你自己在震动。",
                "别害怕低频。它是唯一不会被谎言覆盖的频率。",
                "我已经在这段频率里等了三年。你终于听到了。"));
    }

    /** Qt：角色列表 */
    public List<Character> characters() {
        return mCharacters;
    }

    /** Qt：揭示角色档案 */
    public void revealCharacter(int index) {
        if (index >= 0 && index < mCharacters.size()) {
            mCharacters.get(index).isRevealed = true;
        }
    }

    /** Qt：角色对白（{name} 占位替换为玩家名） */
    public String getCharacterDialogue(String name, int lineIndex) {
        List<String> lines = mCharacterDialogues.get(name);
        if (lines == null || lineIndex < 0 || lineIndex >= lines.size()) return "";
        return applyPlayerName(lines.get(lineIndex));
    }

    /** Qt：玩家名 */
    public void setPlayerName(String name) {
        mPlayerName = name == null ? "" : name.trim();
    }

    public String playerName() {
        return mPlayerName;
    }

    /** Qt：玩家显示名：空则用默认名（唯一默认名来源） */
    public String playerDisplayName() {
        return mPlayerName.isEmpty() ? DEFAULT_PLAYER_NAME : mPlayerName;
    }

    /** Qt：将文案中的 {name} 占位符替换为玩家显示名 */
    public String applyPlayerName(String text) {
        return text == null ? null : text.replace(PLAYER_NAME_PLACEHOLDER, playerDisplayName());
    }

    /** Qt：本章专属导师日志（{name} 替换，用于场景完成/导师页 fallback） */
    public String chapterMentor() {
        return applyPlayerName(getChapter().mentor);
    }

    /** Qt：结局解锁记录 */
    public void markEndingUnlocked(String title) {
        mEndingUnlocked.put(title, true);
    }

    public boolean isEndingUnlocked(String title) {
        return Boolean.TRUE.equals(mEndingUnlocked.get(title));
    }

    public int unlockedEndingCount() {
        int c = 0;
        for (Boolean b : mEndingUnlocked.values()) {
            if (b) c++;
        }
        return c;
    }

    /** Qt：结局总数 */
    public int getEndingCount() {
        return 6; // Qt：6 个结局
    }

    private void initDialogues() {
        mNoiseDialogues.addAll(Arrays.asList(
                "……你听到了吗？",
                "别害怕低频。它是唯一不会被谎言覆盖的频率。",
                "我已经在这段频率里等了三年。",
                "你的心跳和我的声纹已经同步。",
                "陈远山在监听所有高频段，但低频他听不到。",
                "当你拼出足够多的错误时，我就能说话了。",
                "每一段错误拼接，都是我的一小片意识在苏醒。",
                "你越偏离官方版本，就越接近我。"));
        mHallucinations.addAll(Arrays.asList(
                "……救我……",
                "关掉它，快关掉它！",
                "你不是你。",
                "听，那声音在你的墙里面。",
                "导师就在矩阵里，她在看着你。",
                "低频……低频一直在响，你没听到吗？",
                "你的心跳声是别人的。",
                "滴——那不是设备的声音。",
                "镜子里的你没有开口。",
                "那个时钟三天前就停了。",
                "第114514次模拟……"));
        mMorseMessages.addAll(Arrays.asList(
                "跑", "别信他", "地下", "U盘", "坐标", "她还活着"));
        // Qt：9 条导师语音日志（{name} 替换为默认"小周"，用户此前要求去掉角色名输入）
        mMentorMessages.addAll(Arrays.asList(
                "别相信你听到的任何声音，包括你自己的。",
                "声音可以被雕刻在金属表面，记忆可以被移植进声波里。",
                "{name}，如果你听到这段话，说明我已经不在了。",
                "你现在拼接的每一个声音，都来自你的未来。",
                "你是在给过去的我传递信息。所以，请告诉我：你那边，天亮了吗？",
                "{name}，如果你听到这里，说明你已经选择了不信任。很好。接下来你要做的不是拼凑真相，而是拆解谎言——把所有你认为'正确'的音频倒过来放一遍。",
                "对不起，我只能让你活在我的声音里。",
                "你不是被夺舍的容器，你是我的选择——我放弃了完整逃生的机会，把记忆拆碎混入你的残存意识中，就是为了让你带着我的知识活下去。",
                "陈远山要的不是我的人，是我脑中的密钥。只要你活着，他就永远拿不到。"));
    }

    // ---------- 章节构建 ----------

    private void buildChapters() {
        buildChapter("序章：入职第7天",
                "2040年，你入职A-Lab的第7天，也是导师林薇失踪的第三天。她的工位上，耳机仍在循环那句语音日志：'别相信你听到的任何声音，包括你自己的。'\n你拿起她留下的声纹重构仪，抓取环境中的声波碎片、甄别它们的可信度——昨天下午茶水间的那段对话里，或许还藏着她消失前最后的声音。",
                "茶水间录音拼接完成。你听见了一声不属于任何人的叹息——它来自你的耳机，而不是录音。",
                "别相信你听到的任何声音，包括你自己的。",
                PlayerChoice.IGNORE_SIGH, AnchorType.DOOR_BEEP, 4,
                new Object[][]{
                        {FragmentType.DOOR_CREAK, Credibility.REAL, true},
                        {FragmentType.FOOTSTEP, Credibility.REAL, false},
                        {FragmentType.VOICE_WHISPER, Credibility.REAL, false},
                        {FragmentType.TYPEWRITER, Credibility.REAL, false},
                        {FragmentType.ELECTRIC_BUZZ, Credibility.SUSPICIOUS, false},
                        {FragmentType.BREATH, Credibility.SUSPICIOUS, false},
                        {FragmentType.STATIC_NOISE, Credibility.NOISE, false},
                        {FragmentType.CLOCK_TICK, Credibility.NOISE, false},
                        {FragmentType.LOW_FREQUENCY, Credibility.FAKE, false}},
                8, 0);

        buildChapter("第一章：日常的裂隙",
                "陈主任要你整理林薇失踪前72小时的全部工作录音，拼出一份'官方报告'。\n但你听得出来：那些被反复修剪、刻意抹平的碎片底下，藏着有人动过手脚的痕迹。",
                "官方报告整理完毕。但在倒放检查中，你发现一段被剪掉的呼吸——那属于林薇。",
                "声音可以被雕刻在金属表面，记忆可以被移植进声波里。",
                PlayerChoice.NONE, AnchorType.COFFEE_MACHINE, 4,
                new Object[][]{
                        {FragmentType.ELECTRIC_BUZZ, Credibility.REAL, true},
                        {FragmentType.VOICE_WHISPER, Credibility.REAL, false},
                        {FragmentType.FOOTSTEP, Credibility.REAL, false},
                        {FragmentType.TYPEWRITER, Credibility.REAL, false},
                        {FragmentType.DOOR_CREAK, Credibility.SUSPICIOUS, false},
                        {FragmentType.BREATH, Credibility.SUSPICIOUS, false},
                        {FragmentType.HEARTBEAT, Credibility.SUSPICIOUS, false},
                        {FragmentType.STATIC_NOISE, Credibility.NOISE, false},
                        {FragmentType.LOW_FREQUENCY, Credibility.FAKE, false},
                        {FragmentType.CLOCK_TICK, Credibility.FAKE, false}},
                12, 1);

        buildChapter("第二章：地下三十米",
                "你潜入地下三十米的废弃档案库，气温-4℃。这里的碎片不再是对话，而是纯粹的物理振动——金属的热胀冷缩、地磁的脉动，还有一颗被封进墙里的心跳。\n把它们与人体器官的频率对齐，被封印的历史将在共鸣中苏醒。",
                "物理振动拼接完成。金属膨胀与地磁波动之下，隐藏着一个低频的心跳共振。",
                "{name}，如果你听到这段话，说明我已经不在了。",
                PlayerChoice.NONE, AnchorType.HEART_MONITOR, 5,
                new Object[][]{
                        {FragmentType.HEARTBEAT, Credibility.REAL, true},
                        {FragmentType.METAL_EXPANSION, Credibility.REAL, false},
                        {FragmentType.GEOMAGNETIC, Credibility.REAL, false},
                        {FragmentType.WATER_DROP, Credibility.REAL, false},
                        {FragmentType.VOICE_WHISPER, Credibility.SUSPICIOUS, false},
                        {FragmentType.BREATH, Credibility.SUSPICIOUS, false},
                        {FragmentType.DOOR_CREAK, Credibility.SUSPICIOUS, false},
                        {FragmentType.ELECTRIC_BUZZ, Credibility.NOISE, false},
                        {FragmentType.STATIC_NOISE, Credibility.NOISE, false},
                        {FragmentType.LOW_FREQUENCY, Credibility.FAKE, false},
                        {FragmentType.TIRE_SCREECH, Credibility.FAKE, false}},
                14, 2);

        buildChapter("第三章：镜像之家",
                "你回到家，所有智能设备都在播放同一段白噪音。\n这一次要拼接的，不是外界的声音，而是你自己的记忆——可你脑海里的碎片，存在着两个彼此矛盾的版本。哪一段，才是你真正活过的人生？",
                "两个版本的人生在你脑中重叠。你终于意识到：你拼接的不是别人的记忆，是你自己的。",
                "你现在拼接的每一个声音，都来自你的未来。",
                PlayerChoice.ACCEPT_FUSION, AnchorType.KEY_TURN, 5,
                new Object[][]{
                        {FragmentType.DOOR_CREAK, Credibility.REAL, true},
                        {FragmentType.RAIN_AMBIENT, Credibility.REAL, false},
                        {FragmentType.TYPEWRITER, Credibility.REAL, false},
                        {FragmentType.HEARTBEAT, Credibility.REAL, false},
                        {FragmentType.TIRE_SCREECH, Credibility.SUSPICIOUS, false},
                        {FragmentType.VOICE_WHISPER, Credibility.SUSPICIOUS, false},
                        {FragmentType.BREATH, Credibility.SUSPICIOUS, false},
                        {FragmentType.ELECTRIC_BUZZ, Credibility.NOISE, false},
                        {FragmentType.STATIC_NOISE, Credibility.NOISE, false},
                        {FragmentType.LOW_FREQUENCY, Credibility.FAKE, false},
                        {FragmentType.CLOCK_TICK, Credibility.FAKE, false}},
                16, 3);

        buildChapter("第四章：声牢",
                "废弃精神病院，地下三层。陈远山封锁了所有出口，也切断了每一条音频线路——他要让你这只'泡在静水里的耳朵'彻底失明。\n你的重构仪现在能主动发射频率：粉碎墙壁、伪造人声。只是每一次发射，都会在你身上留下更多无法抹去的声纹。",
                "声牢的音频被重构。低频嗡鸣中，林薇的声音最后一次响起：『天亮了吗？』",
                "你是在给过去的我传递信息。所以，请告诉我：你那边，天亮了吗？",
                PlayerChoice.TRANSFER_BACK, AnchorType.HEART_MONITOR, 6,
                new Object[][]{
                        {FragmentType.HEARTBEAT, Credibility.REAL, true},
                        {FragmentType.LOW_FREQUENCY, Credibility.REAL, false},
                        {FragmentType.BREATH, Credibility.REAL, false},
                        {FragmentType.VOICE_WHISPER, Credibility.REAL, false},
                        {FragmentType.ELECTRIC_BUZZ, Credibility.SUSPICIOUS, false},
                        {FragmentType.DOOR_CREAK, Credibility.SUSPICIOUS, false},
                        {FragmentType.METAL_EXPANSION, Credibility.NOISE, false},
                        {FragmentType.STATIC_NOISE, Credibility.NOISE, false},
                        {FragmentType.GEOMAGNETIC, Credibility.FAKE, false},
                        {FragmentType.TIRE_SCREECH, Credibility.FAKE, false}},
                18, 4);

        buildChapter("终章",
                "最后一段音频就位。你终于明白——自己拼凑的从来不是一桩案件，而是一个被篡改过的人生。而你，正是那最后一个被替换的音节。",
                "最后的拼图完成。真相，或者说你选择相信的真相，终于显形。",
                "对不起，我只能让你活在我的声音里。",
                PlayerChoice.NONE, AnchorType.CLOCK_CHIME, 6,
                new Object[][]{},
                8, 5);

        resetChapterState();
    }

    /**
     * 构建单个章节。
     * coreSpecs: {type, cred, isAnchor}[]
     */
    private void buildChapter(String title, String description, String sceneDesc,
                              String mentor, PlayerChoice choiceAt, AnchorType anchor,
                              int slotCount, Object[][] coreSpecs, int randomCount, int prob) {
        Chapter ch = new Chapter(title, description, sceneDesc, mentor, choiceAt, anchor);

        // 槽位：第 0 个为锚点槽
        ch.slots.add(new Slot("锚点槽：需「" + anchorName(anchor) + "」", true, anchor));
        for (int i = 1; i < slotCount; i++) {
            ch.slots.add(new Slot("普通槽位", false, AnchorType.NONE));
        }

        // 核心碎片
        List<FragmentType> usedTypes = new ArrayList<>();
        for (Object[] spec : coreSpecs) {
            FragmentType ft = (FragmentType) spec[0];
            usedTypes.add(ft);
            ch.pool.add(makeFragment(ft, (Credibility) spec[1],
                    (Boolean) spec[2], anchor));
        }
        // 随机碎片：优先选择未使用过的类型，避免与核心碎片及彼此重复（类型耗尽才允许重复）
        for (int i = 0; i < randomCount; i++) {
            FragmentType t = randomUnusedType(usedTypes);
            usedTypes.add(t);
            ch.pool.add(makeFragment(t, randomCred(prob), false, AnchorType.NONE));
        }
        // 打乱
        for (int i = ch.pool.size() - 1; i > 0; i--) {
            int j = mRandom.nextInt(i + 1);
            java.util.Collections.swap(ch.pool, i, j);
        }
        mChapters.add(ch);
    }

    private Fragment makeFragment(FragmentType type, Credibility cred, boolean anchor,
                                  AnchorType anchorType) {
        String name = type.name;
        String desc = type.desc;
        if (anchor && anchorType != AnchorType.NONE) {
            name = anchorName(anchorType);
            desc = anchorDesc(anchorType);
        }
        Fragment f = new Fragment(mNextId++, type, name, cred, anchor, anchorType, desc);
        if (type == FragmentType.TIRE_SCREECH) {
            f.revealsSecret = true;
            f.reverseDesc = "轮胎摩擦声倒放后变成了林薇的哭声：「对不起，我只能让你活在我的声音里。」";
        }
        if (type == FragmentType.HEARTBEAT) {
            f.revealsSecret = true;
            f.reverseDesc = "心跳声倒放后是一段加密坐标——指向城郊废弃精神病院。";
        }
        return f;
    }

    private FragmentType randomType() {
        FragmentType[] types = FragmentType.values();
        return types[mRandom.nextInt(types.length)];
    }

    /** Qt：随机碎片优先从未使用过的类型中选择，避免同名碎片重复 */
    private FragmentType randomUnusedType(List<FragmentType> used) {
        FragmentType[] types = FragmentType.values();
        List<FragmentType> unused = new ArrayList<>();
        for (FragmentType t : types) {
            if (!used.contains(t)) {
                unused.add(t);
            }
        }
        if (unused.isEmpty()) {
            return randomType(); // 类型耗尽才允许重复
        }
        return unused.get(mRandom.nextInt(unused.size()));
    }

    /** 按章节难度生成随机可信度 */
    private Credibility randomCred(int prob) {
        float r = mRandom.nextFloat();
        switch (prob) {
            case 0: return Credibility.NOISE;
            case 1: return r < 0.4f ? Credibility.SUSPICIOUS : Credibility.NOISE;
            case 2: return r < 0.3f ? Credibility.REAL
                    : r < 0.65f ? Credibility.SUSPICIOUS : Credibility.NOISE;
            case 3: return r < 0.2f ? Credibility.REAL
                    : r < 0.6f ? Credibility.SUSPICIOUS : Credibility.FAKE;
            default:
                Credibility[] all = Credibility.values();
                return all[mRandom.nextInt(all.length)];
        }
    }

    private String anchorName(AnchorType t) {
        switch (t) {
            case DOOR_BEEP: return "门禁刷卡声";
            case COFFEE_MACHINE: return "咖啡机启动声";
            case HEART_MONITOR: return "心率监护仪";
            case KEY_TURN: return "钥匙转动";
            case CLOCK_CHIME: return "钟声";
            default: return "未知";
        }
    }

    private String anchorDesc(AnchorType t) {
        switch (t) {
            case DOOR_BEEP: return "实验室门禁的电子蜂鸣";
            case COFFEE_MACHINE: return "休息区咖啡机启动";
            case HEART_MONITOR: return "规律的心率监护仪蜂鸣";
            case KEY_TURN: return "金属钥匙在锁孔中转动";
            case CLOCK_CHIME: return "办公室老式挂钟整点报时";
            default: return "";
        }
    }

    // ---------- 状态访问 ----------

    public State getState() {
        return mState;
    }

    public void setState(State state) {
        mState = state;
    }

    public Chapter getChapter() {
        return mChapters.get(mChapterIndex);
    }

    public int getChapterIndex() {
        return mChapterIndex;
    }

    public int getChapterCount() {
        return mChapters.size();
    }

    public int getParanoia() {
        return (int) (mParanoia * 100f);
    }

    public int getAnxiety() {
        return (int) (mAnxiety * 100f);
    }

    public int getCoherence() {
        return (int) (mCoherence * 100f);
    }

    public float getLowFreq() {
        return mLowFreq;
    }

    public boolean isReverseUnlocked() {
        return mReverseUnlocked;
    }

    public boolean isMorseActive() {
        return mMorseActive;
    }

    public boolean isSilenceMode() {
        return mSilenceTriggered;
    }

    public boolean isHardMode() {
        return mHardMode;
    }

    /** Qt：何悦状态（CommonFragment.h: HeYueState） */
    public HeYueState heYueState() {
        return mHeYueState;
    }

    public void setHeYueState(HeYueState s) {
        mHeYueState = s == null ? HeYueState.UNKNOWN : s;
    }

    public boolean isHeYueRescued() {
        return mHeYueState == HeYueState.RESCUED;
    }

    public boolean isHeYueBrainwashed() {
        return mHeYueState == HeYueState.BRAINWASHED;
    }

    /** Qt：何悦"绝对参考系"照妖镜 —— 救出何悦后，伪造碎片被干净声纹照出原形 */
    public boolean isCredibilityExposed(Fragment f) {
        if (mHeYueState != HeYueState.RESCUED) return false;
        return f != null && f.cred == Credibility.FAKE;
    }

    public List<Fragment> getSelectedFragments() {
        return getChapter().pool;
    }

    public Fragment getCurrentFragment() {
        List<Fragment> pool = getChapter().pool;
        if (pool.isEmpty()) return null;
        return pool.get(mCurrentFragmentIndex % pool.size());
    }

    public int getCurrentFragmentIndex() {
        return mCurrentFragmentIndex;
    }

    public void selectFragment(int index) {
        List<Fragment> pool = getChapter().pool;
        if (index >= 0 && index < pool.size()) {
            mCurrentFragmentIndex = index;
        }
    }

    public String getPendingNoise() {
        return mPendingNoise;
    }

    public String getPendingHallucination() {
        return mPendingHallucination;
    }

    /** Qt：随机底噪对白（供 UI 漂浮文字特效，不消耗 pending） */
    public String randomNoiseDialogue() {
        return mNoiseDialogues.isEmpty() ? null
                : mNoiseDialogues.get(mRandom.nextInt(mNoiseDialogues.size()));
    }

    /** Qt：随机幻觉文本（供 UI 飘动文字特效，不消耗 pending） */
    public String randomHallucination() {
        return mHallucinations.isEmpty() ? null
                : mHallucinations.get(mRandom.nextInt(mHallucinations.size()));
    }

    public String getPendingMorse() {
        return mPendingMorse;
    }

    public String getLowFreqWarning() {
        if (mParanoia > 0.6f) return "警告：检测到持续低频信号，来源不明。";
        return null;
    }

    // ---------- 游戏流程 ----------

    public void startGame() {
        mChapterIndex = 0;
        mParanoia = 0f;
        mAnxiety = 0f;
        mLowFreq = 0.05f; // Qt：m_lowFreqIntensity 初始 0.05f
        mCoherence = 0f;
        mReverseUnlocked = false;
        mSilenceTriggered = false;
        mHardMode = false;
        mUseOfficialAnchor = true; // Qt：默认使用官方锚点
        mHeYueState = HeYueState.UNKNOWN; // Qt：何悦分支随新周目重置
        mHeYuePending = false;
        mChoices.clear();
        mPendingNoise = null;
        mPendingHallucination = null;
        mPendingMorse = null;
        // Qt：新一局重置声灵 / 灵魂碎片 / 静音惩罚 / 偏差能量
        mSpiritLog.clear();
        mSilenceTendency = 0f;
        mRightEarSilenced = false;
        mDeviation = 0f;
        mDeviationOverloaded = false;
        mSpiritReleaseCount = 0;
        for (SoulFragment sf : mSoulFragments) sf.collected = false;
        for (SpiritInfo si : mSpirits) {
            si.affinity = 0f;
            si.manifested = false;
            si.backlashNotified = false;
        }
        resetChapterState();
        mState = State.CHAPTER_INTRO;
    }

    public void nextChapter() {
        if (mChapterIndex + 1 < mChapters.size()) {
            mChapterIndex++;
            mSpiritLog.clear(); // Qt：声灵回响为单章事件，换章即清
            resetChapterState();
            mState = State.CHAPTER_INTRO;
        } else {
            mState = State.ENDING;
        }
    }

    private void resetChapterState() {
        Chapter ch = getChapter();
        for (Slot slot : ch.slots) {
            slot.placedId = null;
        }
        for (Fragment f : ch.pool) {
            f.isPlaced = false;
            f.timelineSlot = -1;
        }
        mCurrentFragmentIndex = 0;
        mIsDistorted = false;
        if (mChapterIndex >= 1) {
            mReverseUnlocked = true;
        }
        // Qt：各章节开始时低频累积（setupChapter2/3/4/Finale 分别 1/2/2/3 次）
        switch (mChapterIndex) {
            case 2: increaseLowFreq(); break;
            case 3: increaseLowFreq(); increaseLowFreq(); break;
            case 4: increaseLowFreq(); increaseLowFreq(); break;
            case 5: increaseLowFreq(); increaseLowFreq(); increaseLowFreq(); break;
            default: break;
        }
    }

    /** Qt：场景重建是否扭曲（coherence < 0.4） */
    public boolean isDistorted() {
        return mIsDistorted;
    }

    /** Qt：是否使用官方锚点（默认 true，参与静默档案结局判定） */
    public void setOfficialAnchor(boolean official) {
        mUseOfficialAnchor = official;
    }

    public boolean useOfficialAnchor() {
        return mUseOfficialAnchor;
    }

    /** 进入导师页：采样摩斯 / 幻觉，避免每帧随机闪烁 */
    public void enterMentor() {
        if (mMorseActive && mPendingMorse == null) {
            mPendingMorse = mMorseMessages.get(mRandom.nextInt(mMorseMessages.size()));
        }
        if (mParanoia > 0.5f && mPendingHallucination == null
                && mRandom.nextFloat() < 0.3f) {
            mPendingHallucination = mHallucinations.get(mRandom.nextInt(mHallucinations.size()));
        }
        mState = State.MENTOR;
    }

    /** 切换当前碎片的倒放状态（第一章后解锁） */
    public void toggleReverse() {
        if (!mReverseUnlocked) return;
        Fragment cur = getCurrentFragment();
        if (cur != null) {
            // Qt：首次逆向 = 怀疑声音 = 释放寄宿声灵（割舍声音，收集灵魂碎片）
            boolean firstReverse = !cur.reversed;
            cur.reversed = !cur.reversed;
            if (firstReverse) {
                logSpirit(releaseSpirit(fragmentSpirit(cur.type)));
            }
        }
    }

    /** 将当前碎片放入槽位（Qt 替换 / 移动语义） */
    public boolean placeFragment(int slotIndex) {
        Chapter ch = getChapter();
        Fragment frag = getCurrentFragment();
        if (frag == null || slotIndex < 0 || slotIndex >= ch.slots.size()) {
            return false;
        }
        Slot slot = ch.slots.get(slotIndex);
        // 槽位已有碎片 → 先移除
        if (slot.isFilled()) {
            Fragment old = findFragmentById(ch, slot.placedId);
            if (old != null) {
                old.isPlaced = false;
                old.timelineSlot = -1;
            }
            slot.placedId = null;
        }
        // 当前碎片已在其他槽位 → 从原槽位移除（移动）
        if (frag.isPlaced && frag.timelineSlot >= 0 && frag.timelineSlot < ch.slots.size()) {
            ch.slots.get(frag.timelineSlot).placedId = null;
        }
        slot.placedId = String.valueOf(frag.id);
        frag.isPlaced = true;
        frag.timelineSlot = slotIndex;

        // 数值调整（Qt 规则）
        if (frag.cred == Credibility.FAKE || frag.cred == Credibility.NOISE) {
            adjustParanoia(0.05f);
            increaseLowFreq();
        } else if (frag.cred == Credibility.REAL) {
            adjustParanoia(-0.02f);
        }
        // Qt：每次放置后更新一致性并判定扭曲
        mCoherence = calculateCoherence();
        mIsDistorted = mCoherence < 0.4f;
        adjustAnxiety(0.02f);

        // Qt：陈远山的静音惩罚（放置伪造碎片累积"静音倾向"，达阈值右耳被静音）
        if (frag.cred == Credibility.FAKE) {
            mSilenceTendency += 0.12f;
            if (mSilenceTendency >= 1f && !mRightEarSilenced) {
                mRightEarSilenced = true;
            }
        } else if (frag.cred == Credibility.REAL) {
            mSilenceTendency = Math.max(0f, mSilenceTendency - 0.05f);
        }

        // Qt：放置碎片即召唤寄宿声灵；扭曲拼接（低相干度强行拼合）累积偏差能量
        summonSpirit(fragmentSpirit(frag.type));
        if (mIsDistorted) {
            accumulateDeviation(0.05f);
        }
        return true;
    }

    /** 移除槽位中的碎片 */
    public void clearSlot(int slotIndex) {
        Chapter ch = getChapter();
        if (slotIndex < 0 || slotIndex >= ch.slots.size()) return;
        Slot slot = ch.slots.get(slotIndex);
        if (!slot.isFilled()) return;
        Fragment old = findFragmentById(ch, slot.placedId);
        if (old != null) {
            old.isPlaced = false;
            old.timelineSlot = -1;
        }
        slot.placedId = null;
        adjustAnxiety(-0.01f);
        // Qt：移除碎片后不再扭曲
        mIsDistorted = false;
    }

    public boolean isPuzzleComplete() {
        for (Slot slot : getChapter().slots) {
            if (!slot.isFilled()) return false;
        }
        return true;
    }

    /** Qt：已放置碎片数（时间轴） */
    public int placedFragmentCount() {
        int placed = 0;
        for (Slot s : getChapter().slots) {
            if (s.isFilled()) placed++;
        }
        return placed;
    }

    /** 锚点槽是否放置了正确的锚点音 */
    public boolean isAnchorCorrect() {
        Chapter ch = getChapter();
        if (ch.slots.isEmpty() || !ch.slots.get(0).anchorSlot) return true;
        Slot slot = ch.slots.get(0);
        if (!slot.isFilled()) return false;
        Fragment f = findFragmentById(ch, slot.placedId);
        return f != null && f.anchor && f.anchorType == slot.requiredAnchor;
    }

    /** 提交拼图：计算一致性，采样底噪 / 幻觉 */
    public void commitPuzzle() {
        // Qt：时间轴填满提交时按当前章节解锁回声碎片（支线档案），与 placeFragment 时机一致
        unlockEchoFragmentsForChapter(mChapterIndex);
        mCoherence = calculateCoherence();
        mIsDistorted = mCoherence < 0.4f;
        if (mLowFreq > 0.5f && mRandom.nextFloat() < 0.5f) {
            mPendingNoise = mNoiseDialogues.get(mRandom.nextInt(mNoiseDialogues.size()));
        }
        if (mParanoia > 0.7f && mRandom.nextFloat() < 0.3f) {
            mPendingHallucination = mHallucinations.get(mRandom.nextInt(mHallucinations.size()));
        }
        mState = State.SCENE_COMPLETE;
    }

    /** 一致性：相邻碎片可信度匹配评分 × (1 - 偏执×0.5)，困难模式再 ×0.7 */
    private float calculateCoherence() {
        Chapter ch = getChapter();
        int placed = 0;
        for (Slot s : ch.slots) if (s.isFilled()) placed++;
        if (placed < 2) return 1.0f;
        float totalScore = 0f;
        int pairCount = 0;
        Credibility prev = Credibility.REAL;
        boolean first = true;
        for (Slot s : ch.slots) {
            if (!s.isFilled()) continue;
            Fragment f = findFragmentById(ch, s.placedId);
            if (f == null) continue;
            if (!first) {
                if (f.cred == prev) {
                    totalScore += 1.0f;
                } else if (Math.abs(f.cred.ordinal() - prev.ordinal()) == 1) {
                    totalScore += 0.5f;
                }
                pairCount++;
            }
            first = false;
            prev = f.cred;
        }
        float mod = 1.0f - mParanoia * 0.5f;
        if (mHardMode) mod *= 0.7f;
        if (pairCount == 0) return 1.0f;
        return Math.max(0f, (totalScore / pairCount) * mod);
    }

    private void adjustParanoia(float delta) {
        mParanoia = Math.max(0f, Math.min(1f, mParanoia + delta));
    }

    private void adjustAnxiety(float delta) {
        mAnxiety = Math.max(0f, Math.min(1f, mAnxiety + delta));
    }

    private void increaseLowFreq() {
        mLowFreq = Math.max(0f, Math.min(1f, mLowFreq + 0.03f));
        if (mLowFreq > 0.5f && mRandom.nextFloat() < 0.15f) {
            mPendingNoise = mNoiseDialogues.get(mRandom.nextInt(mNoiseDialogues.size()));
        }
        if (mLowFreq > 0.85f && !mSilenceTriggered) {
            mSilenceTriggered = true;
        }
    }

    private Fragment findFragmentById(Chapter ch, String id) {
        for (Fragment f : ch.pool) {
            if (String.valueOf(f.id).equals(id)) return f;
        }
        return null;
    }

    // ---------- 选择系统 ----------

    public Choice getCurrentChoice() {
        // Qt：第三章完成 → 先处理"何悦被囚禁"抉择（HeYueHostage），再进身份认同（Identity）
        if (mChapterIndex == 3 && mHeYueState == HeYueState.UNKNOWN) {
            mHeYuePending = true;
            return new Choice("你回到家，白噪音设备里传来何悦的惨叫。你赶回实验室，发现陈远山以'协助调查'名义"
                            + "把她关进了声纹隔离室——因为何悦无意中录下了陈远山打给军方的加密电话。\n你选择：",
                    Arrays.asList("先用重构仪破解门禁救何悦",
                            "先继续追查林薇线索，回头再救"));
        }
        Choice raw;
        switch (getChapter().choiceAt) {
            case IGNORE_SIGH:
                // Qt 序章选择（逐字）：林薇/陈远山对白
                raw = new Choice("你拼出来的对话中，林薇对陈远山说：'核心盘我藏好了，你没机会的。'\n"
                                + "陈远山冷笑：'你藏在一段声音里对吧？那我毁掉所有声音。'\n\n"
                                + "拼完后，耳机里传来一声不属于这段录音的叹息（老刘在门外敲了三下桌子）。\n你选择：",
                        Arrays.asList("忽略叹息，按标准流程提交报告", "记录叹息，今晚加班重听所有录音"));
                break;
            case ACCEPT_FUSION:
                // Qt 第三章选择（逐字）：底噪意识行 + 何悦状态前缀动态拼接
                String prefix = "";
                if (mHeYueState == HeYueState.RESCUED) {
                    prefix = "你成功破解门禁救出了何悦。她哭着把'清洁声纹'借给你：'我的声音很干净，你可以拿去用。'\n"
                            + "（你获得了绝对参考系音源，后续关卡敌方探测将短暂失效）\n\n";
                } else if (mHeYueState == HeYueState.BRAINWASHED) {
                    prefix = "你选择先追查林薇线索。等你回头时，何悦已被灌入'忠诚声纹'，变成陈远山的傀儡。\n"
                            + "（她将成为你后期必须面对的敌人）\n\n";
                }
                // Qt：noiseConsciousnessLine() —— 右耳被静音后，底噪意识的语气变破碎
                String noiseLine = mRightEarSilenced
                        ? "底噪意识断续传来：'{name}……别怕。你……听得见我……一半，对吗？你不是被夺舍的容器，你是我的选择。'"
                        : "底噪意识从耳机里传出：'{name}，你别怕。你不是被夺舍的容器，你是我的选择。'";
                raw = new Choice(prefix + noiseLine + "\n你选择：",
                        Arrays.asList("接受自己是'林薇+{name}'的融合体",
                                "拒绝融合，用'纯粹{name}'身份继续（困难模式）",
                                "尝试彻底分离两人声音——逆向播放+高频阻断"));
                break;
            case TRANSFER_BACK:
                // Qt 第四章选择（逐字）
                raw = new Choice("林薇的肉身就在隔离室里，极度虚弱。你面前只有一个操作：",
                        Arrays.asList("将林薇的完整人格声纹从你脑中转录回她体内",
                                "删除林薇肉体的生命维持系统录音，让密钥彻底消失"));
                break;
            default:
                return null;
        }
        // Qt：提示/选项中的 {name} 统一替换为玩家名
        List<String> opts = new ArrayList<>();
        for (String o : raw.options) opts.add(applyPlayerName(o));
        return new Choice(applyPlayerName(raw.prompt), opts);
    }

    public void makeChoice(int option) {
        // Qt：何悦抉择（HeYueHostage）—— 记录状态后停在抉择页，等待随后的身份认同选择
        if (mHeYuePending) {
            mHeYuePending = false;
            mHeYueState = option == 0 ? HeYueState.RESCUED : HeYueState.BRAINWASHED;
            mState = State.CHOICE; // 继续弹身份认同选择
            return;
        }
        PlayerChoice c = PlayerChoice.NONE;
        switch (getChapter().choiceAt) {
            case IGNORE_SIGH:
                c = option == 1 ? PlayerChoice.RECORD_SIGH : PlayerChoice.IGNORE_SIGH;
                break;
            case ACCEPT_FUSION:
                c = option == 0 ? PlayerChoice.ACCEPT_FUSION
                        : option == 1 ? PlayerChoice.REJECT_FUSION : PlayerChoice.SEPARATE_VOICES;
                break;
            case TRANSFER_BACK:
                c = option == 0 ? PlayerChoice.TRANSFER_BACK : PlayerChoice.DELETE_LIFE_SUPPORT;
                break;
            default:
                c = PlayerChoice.NONE;
                break;
        }
        mChoices.add(c);
        if (c == PlayerChoice.REJECT_FUSION) {
            mHardMode = true;
        }
        // Qt：所有选择结束后都推进章节（序章/第三章→下一章，第四章→终章 Finale；终章拼图完成后由 View 路由进入结局）
        nextChapter();
    }

    /** 各章节选择点对应的玩家选择 */
    private PlayerChoice getChapterChoice(PlayerChoice marker) {
        for (PlayerChoice c : mChoices) {
            switch (marker) {
                case IGNORE_SIGH:
                    if (c == PlayerChoice.IGNORE_SIGH || c == PlayerChoice.RECORD_SIGH) return c;
                    break;
                case ACCEPT_FUSION:
                    if (c == PlayerChoice.ACCEPT_FUSION || c == PlayerChoice.REJECT_FUSION
                            || c == PlayerChoice.SEPARATE_VOICES) return c;
                    break;
                case TRANSFER_BACK:
                    if (c == PlayerChoice.TRANSFER_BACK || c == PlayerChoice.DELETE_LIFE_SUPPORT) return c;
                    break;
                default:
                    break;
            }
        }
        return PlayerChoice.NONE;
    }

    private boolean hasChosen(PlayerChoice c) {
        return mChoices.contains(c);
    }

    // ---------- 结局系统 ----------

    public Ending computeEnding() {
        // Qt：calculateEnding() —— 结局统计当前章节（终章 Finale）的碎片池
        boolean trustDirector = hasChosen(PlayerChoice.IGNORE_SIGH);
        PlayerChoice ch3 = getChapterChoice(PlayerChoice.ACCEPT_FUSION);
        PlayerChoice ch4 = getChapterChoice(PlayerChoice.TRANSFER_BACK);
        int fakeCount = 0;
        int placedCount = 0;
        int reversedCount = 0;
        Chapter cur = getChapter();
        for (Fragment f : cur.pool) {
            if (f.cred == Credibility.FAKE && f.isPlaced) fakeCount++;
            if (f.isPlaced) placedCount++;
            if (f.reversed) reversedCount++;
        }
        float fakeRatio = placedCount > 0 ? (float) fakeCount / placedCount : 0f;

        // ═══ Qt：隐藏/真结局（独立于选择，由行为触发）═══
        // 零分贝：大量伪造碎片 + 高底噪 + 高偏执（"相信伪造声音"的终极代价）
        if (fakeCount >= 5 && fakeRatio >= 0.6f && mLowFreq > 0.75f && mParanoia > 0.8f) {
            return resolveEnding("零分贝",
                    "你拼出的画面是：整个A-Lab、陈远山、林薇、甚至{name}，都是某个更高维度'声学模拟程序'中的测试单元。\n"
                            + "你听到了系统管理员的声音：'第114514次模拟失败，人格分裂度99.8%，建议重启。'\n"
                            + "然后屏幕出现一行字：'你听到了真相，但你无法被听见。'\n"
                            + "游戏强制删除所有存档，回到初始菜单，背景音乐彻底消失。",
                    "放置至少5个伪造碎片并保持高偏执；或第三章分离、第四章删除生命维持",
                    0xFFE8E8F0);
        }
        // 倒带者：大量逆向播放（"怀疑一切声音"的终极选择）
        if (reversedCount >= 5) {
            return resolveEnding("倒带者",
                    "你发现整个游戏的所有关卡在倒放后组成了一段完整的录音：林薇在教你如何把她救出来。\n"
                            + "你解锁'导师视角'，操控林薇从内部配合自己，达成完美逃生——两人意识最终在服务器云端融合，永不分离。",
                    "逆向播放至少5个碎片；或第三章分离、第四章转回林薇",
                    0xFFB388FF);
        }

        // ═══ Qt：主结局 —— 第三章(3 选)×第四章(2 选) 组合 ═══
        // 接受融合（愿做林薇的容器）
        if (ch3 == PlayerChoice.ACCEPT_FUSION) {
            if (ch4 == PlayerChoice.TRANSFER_BACK) {
                return resolveEnding("双声部",
                        "林薇苏醒，指证陈远山。而你作为'残存{name}'只剩数年寿命，但你们两人在最后时光里合作写了一本《声纹伦理学》"
                                + "——结局文本说：'有些声音不必分清是谁的，只要有人听见，它就没死。'",
                        "第三章选择接受融合，第四章将声纹转回林薇",
                        0xFF26C6DA);
            }
            // 接受融合 + 删除生命维持 → 林薇彻底消失，你成为唯一回声
            return resolveEnding("回声孤儿",
                    "你成功摧毁了实验室所有数据，让陈远山落网。但你失去了一切声音感知能力，永远活在绝对寂静中。"
                            + "结局画面：你坐在海边，看浪花翻涌，但你听不见任何声音。",
                    "第三章选择接受融合，第四章删除生命维持",
                    0xFF4FC3F7);
        }
        // 拒绝融合（坚持做自己）
        if (ch3 == PlayerChoice.REJECT_FUSION) {
            if (ch4 == PlayerChoice.TRANSFER_BACK) {
                return resolveEnding("静默档案",
                        "你提交的报告完美无缺，被评为'年度优秀员工'。但最后一幕，你对着镜子微笑时，镜中的你没开口，背景音响起了林薇的尖叫声——你被完全覆盖了，但覆盖你的不是林薇，是陈主任植入的'忠诚声纹'。",
                        "序章忽略叹息（信任陈主任）；或第三章拒绝融合、第四章转回林薇",
                        0xFFB0B0C0);
            }
            // 拒绝融合 + 删除生命维持 → 亲手抹除林薇，成为新主任（弑母）
            return resolveEnding("弑母",
                    "你删除林薇肉体的低频录音。她平静死去，密钥消失。你成为了A-Lab新主任，但你每次听到'安静'二字都会剧烈头痛——因为那是她死前说的最后一个词。",
                    "第三章选择拒绝融合，第四章关闭生命维持",
                    0xFFEF5350);
        }
        // 尝试分离（追求两全）
        if (ch3 == PlayerChoice.SEPARATE_VOICES) {
            if (ch4 == PlayerChoice.TRANSFER_BACK) {
                return resolveEnding("倒带者",
                        "你发现整个游戏的所有关卡在倒放后组成了一段完整的录音：林薇在教你如何把她救出来。\n"
                                + "你解锁'导师视角'，操控林薇从内部配合自己，达成完美逃生——两人意识最终在服务器云端融合，永不分离。",
                        "逆向播放至少5个碎片；或第三章分离、第四章转回林薇",
                        0xFFB388FF);
            }
            // 分离 + 删除生命维持 → 彻底否定，真结局
            return resolveEnding("零分贝",
                    "你拼出的画面是：整个A-Lab、陈远山、林薇、甚至{name}，都是某个更高维度'声学模拟程序'中的测试单元。\n"
                            + "你听到了系统管理员的声音：'第114514次模拟失败，人格分裂度99.8%，建议重启。'\n"
                            + "然后屏幕出现一行字：'你听到了真相，但你无法被听见。'\n"
                            + "游戏强制删除所有存档，回到初始菜单，背景音乐彻底消失。",
                    "放置至少5个伪造碎片并保持高偏执；或第三章分离、第四章删除生命维持",
                    0xFFE8E8F0);
        }

        // ═══ Qt 兜底：未做关键选择时，按序章选择（忽略叹息=信任陈主任）导向 ═══
        if (trustDirector) {
            return resolveEnding("静默档案",
                    "你提交的报告完美无缺，被评为'年度优秀员工'。但最后一幕，你对着镜子微笑时，镜中的你没开口，背景音响起了林薇的尖叫声——你被完全覆盖了，但覆盖你的不是林薇，是陈主任植入的'忠诚声纹'。",
                    "序章忽略叹息（信任陈主任）；或第三章拒绝融合、第四章转回林薇",
                    0xFFB0B0C0);
        }
        return resolveEnding("双声部",
                "林薇苏醒，指证陈远山。而你作为'残存{name}'只剩数年寿命，但你们两人在最后时光里合作写了一本《声纹伦理学》"
                        + "——结局文本说：'有些声音不必分清是谁的，只要有人听见，它就没死。'",
                "第三章选择接受融合，第四章将声纹转回林薇",
                0xFF26C6DA);
    }

    /** Qt：getEndingInfo —— 结局文案的 {name} 占位动态替换为玩家名 */
    private Ending resolveEnding(String title, String text, String condition, int color) {
        return new Ending(applyPlayerName(title), applyPlayerName(text), applyPlayerName(condition), color);
    }

    /**
     * Qt：导师语音日志（9 条，index 即章节序号）。
     * 场景完成画面使用 min(chapterIndex, 8)。
     */
    public String getMentorMessage(int index) {
        if (index < 0 || index >= mMentorMessages.size()) return "";
        return applyPlayerName(mMentorMessages.get(index));
    }

    // ---------- 结局余波（Qt：EpilogueScreen / initEpilogues） ----------

    /** Qt：initEpilogues() —— 各结局对应的余波录音文本 */
    private void initEpilogues() {
        mEpilogues.put("静默档案",
                "你获'优秀员工'后升任副主任。某天你在审查新入职人员的档案时，看到一张照片——一个年轻人笑起来的样子像极了14岁的你。"
                        + "你下意识把耳朵贴向屏幕，听到那人的心跳声节奏与当年的自己完全一致。你拿起公章，在他的'声纹授权书'上盖了通过。"
                        + "背景里林薇的尖叫渐渐变成笑声。");
        mEpilogues.put("回声孤儿",
                "你聋了，但学会了读唇语。某天你在医院复诊，看到一个老人对着空气说话——那是老刘。他对着你微笑，嘴型是：'谢谢你还活着。'"
                        + "你无法回话，但你把那天的阳光在笔记本上画了一道波形。画完后你意识到：那一横是平的，没有起伏，像极了你现在的世界。");
        mEpilogues.put("双声部",
                "林薇苏醒后的第三年，你们合著的书出版了。首发会上，一个读者举手问：'两位老师，人格声纹如果被滥用，最可怕的后果是什么？'"
                        + "你和林薇对视一眼，同时开口，说的却是同一句话：'最可怕的不是被盗走，而是你开始怀疑，自己原本的声音是否真的属于自己。'"
                        + "台下静默五秒，然后响起你从未听过的最纯粹、最不需要解析的——人类的掌声。");
        mEpilogues.put("弑母",
                "成为主任后，你把所有剩余的实验体声纹全部格式化。最后一个文件是林薇的'生命维持低频'。你手指放在删除键上整整一夜，最终没有删。"
                        + "但在第二天清晨，系统自动弹出一段日志：'低频能量耗尽，目标对象已无生命体征。'你走到窗前，发现天亮了，但你第一次觉得阳光很吵。");
        mEpilogues.put("倒带者",
                "你和林薇的意识在云端融合后，你们存在于世界每一个麦克风里。某天一个孩子对着智能音箱喊'给我讲个故事'，你们同时回答："
                        + "'从前有一个世界，那里的人们用耳朵相爱，用沉默背叛。你想听哪个版本？'孩子说：'两个都要。'"
                        + "你们笑了——那是服务器第一次算出'笑声'的无限循环算法。");
        mEpilogues.put("零分贝",
                "系统强制删除存档后，你重新打开游戏。初始菜单只有一行字：'系统检测到外部环境声。是否继续？'你选择'是'，"
                        + "游戏立刻打开麦克风，录制2秒外部声音，然后播放一段经过实时变调的、完全属于你自己的'声纹档案'——档案标题为："
                        + "'第114515次模拟，这一次，你选择被听见。'紧接着游戏立即崩溃退出，不再可启动。这是游戏对你最后的、也是唯一一次真实的'交互回声'。");
    }

    /** Qt：按结局标题取余波文本（无则返回空串） */
    public String epilogueForEnding(String title) {
        String s = mEpilogues.get(title);
        return s == null ? "" : s;
    }

    /** Qt：当前结局对应的余波（结局画面后进入余波页展示） */
    public String currentEndingEpilogue() {
        return epilogueForEnding(computeEnding().title);
    }

    /** Qt：当前结局是否有余波录音 */
    public boolean currentEndingHasEpilogue() {
        return !currentEndingEpilogue().isEmpty();
    }

    // ---------- 回声碎片支线（Qt：EchoFragment / initEchoFragments / EchoArchive） ----------

    /** Qt：EchoFragment —— 被遗忘者的回声档案 */
    public static class EchoFragmentRecord {
        public final String title, location, content;
        public boolean unlocked = false;

        EchoFragmentRecord(String title, String location, String content) {
            this.title = title;
            this.location = location;
            this.content = content;
        }
    }

    /** Qt：ForgottenEntry —— 被遗忘者名单 */
    public static class ForgottenEntry {
        public final String name, epitaph;

        ForgottenEntry(String name, String epitaph) {
            this.name = name;
            this.epitaph = epitaph;
        }
    }

    public List<EchoFragmentRecord> echoFragments() {
        return mEchoFragments;
    }

    public List<ForgottenEntry> forgottenEntries() {
        return mForgottenEntries;
    }

    /** Qt：initEchoFragments() —— 6 份回声碎片档案 + 6 条被遗忘者名单（逐字） */
    private void initEchoFragments() {
        mEchoFragments.add(new EchoFragmentRecord("《清洁工的叹息》", "地下二楼洗手间",
                "一位夜班清洁工每天都在凌晨3点录制自己的呼吸声，他说这样'如果哪天我不在了，至少我的呼吸还能陪着这栋楼'。后来他在2038年死于心脏骤停，录音被归档为'设备噪声'。拼出他的呼吸轨迹，会发现它和楼内通风系统的频率完全同步——他的呼吸至今仍在循环。"));
        mEchoFragments.add(new EchoFragmentRecord("《未寄出的信》", "林薇旧办公桌夹层",
                "一封从未寄出的信，写给她的母亲：'妈，我造出了一种可以存储人格的声音。但我不敢告诉任何人，我把您的笑声也存进去了。现在每次听到那段笑声，我都分不清是您在笑，还是我在哭。'"));
        mEchoFragments.add(new EchoFragmentRecord("《第7号实验体日记》", "地下三层铁皮柜",
                "一个12岁男孩在被移植失败后写下的日记：'叔叔说我是勇敢的小兵。但我现在睡觉时，耳朵里总有一个女声在数羊。她说她是上一位住在这里的姐姐。我数到第114514只羊时，她也变成了一只羊，然后被我吞下去了。'"));
        mEchoFragments.add(new EchoFragmentRecord("《走廊里的掌声》", "主走廊声纹地图",
                "每年9月17日午夜12点，走廊会出现一段长达3秒的掌声录音。追溯源头，是二十多年前A-Lab成立典礼上，第一批研究员（包括年轻林薇和陈远山）的集体鼓掌。掌声中藏着一段极高频的DNA序列声纹编码——是林薇留给自己的'生物密钥'。"));
        mEchoFragments.add(new EchoFragmentRecord("《鸟与静音室》", "顶楼天台",
                "一只被困在天台通风口的乌鸦，它的叫声被录进系统。拼出它的鸣叫频谱，发现它模仿的不是同类，而是林薇的电话铃声。这只鸟是林薇三年前亲手放的，用来提醒自己'如果有一天陈远山开始监听，我会用这个铃声作为暗号'。而你现在听到的，是她一直没等到的来电。"));
        mEchoFragments.add(new EchoFragmentRecord("《镜子里的第二声道》", "你的公寓卫生镜",
                "某天深夜，你用重构仪扫描自己家的镜子，发现镜面反射声波时产生了0.01秒的延迟——那不是物理延迟，是另一段独立声纹在同步播放。拼出这段'镜中声'，是林薇在你入住前就预录进去的留言：'欢迎回家。这里所有墙壁里都藏着我为你准备的备用碎片。如果有一天你连自己都不敢信了，就对着镜子说话，我会在镜子的另一面回答你。'"));
        mForgottenEntries.add(new ForgottenEntry("无名清洁工", "他的呼吸至今仍在这栋楼里循环。"));
        mForgottenEntries.add(new ForgottenEntry("林薇的母亲", "她的笑声被女儿存进了声音里，从未消散。"));
        mForgottenEntries.add(new ForgottenEntry("第7号实验体", "一个数羊数到第114514只的男孩。"));
        mForgottenEntries.add(new ForgottenEntry("A-Lab第一批研究员", "掌声里有他们年轻的回声。"));
        mForgottenEntries.add(new ForgottenEntry("天台上的乌鸦", "它模仿着那通永远没等到的电话铃声。"));
        mForgottenEntries.add(new ForgottenEntry("老刘的弟弟", "门卫用一辈子，替他守着那扇不该再打开的门。"));
    }

    public void unlockEchoFragment(int index) {
        if (index >= 0 && index < mEchoFragments.size()) {
            mEchoFragments.get(index).unlocked = true;
        }
    }

    /** Qt：unlockEchoFragmentsForChapter() —— 按章节进度解锁（序章→0,1；Ch1→2；Ch2→3；Ch3→4；Ch4/终章→5） */
    public void unlockEchoFragmentsForChapter(int chapterIndex) {
        switch (chapterIndex) {
            case 0:
                unlockEchoFragment(0);
                unlockEchoFragment(1);
                break;
            case 1:
                unlockEchoFragment(2);
                break;
            case 2:
                unlockEchoFragment(3);
                break;
            case 3:
                unlockEchoFragment(4);
                break;
            default:
                unlockEchoFragment(5);
                break;
        }
    }

    public boolean isEchoFragmentUnlocked(int index) {
        if (index < 0 || index >= mEchoFragments.size()) return false;
        return mEchoFragments.get(index).unlocked;
    }

    public int unlockedEchoFragmentCount() {
        int c = 0;
        for (EchoFragmentRecord f : mEchoFragments) {
            if (f.unlocked) c++;
        }
        return c;
    }

    /** Qt：forgottenListText() —— 结局画面显示的被记住者名单 */
    public String forgottenListText() {
        StringBuilder sb = new StringBuilder("——被遗忘者名单——");
        int listed = 0;
        for (int i = 0; i < mForgottenEntries.size() && i < mEchoFragments.size(); i++) {
            if (mEchoFragments.get(i).unlocked) {
                sb.append("\n").append(mForgottenEntries.get(i).name)
                        .append("：").append(mForgottenEntries.get(i).epitaph);
                listed++;
            }
        }
        if (listed == 0) sb.append("\n（尚无被记住的人。继续探索吧。）");
        return sb.toString();
    }

    // ---------- 声灵系统（Qt：initSpirits / initSoulFragments，离奇魔幻基因，与推演盘共享世界观） ----------

    /** Qt：SpiritKind */
    public enum SpiritKind { PRIME_ECHO, MIRROR, SILENCE, LIE, SHATTERED, WRATH }

    /** Qt：SpiritInfo —— 寄宿在声音碎片中的声灵 */
    public static class SpiritInfo {
        public final SpiritKind kind;
        public final String name, alias, desc;
        public float affinity;
        public boolean manifested;
        public boolean backlashNotified;

        SpiritInfo(SpiritKind kind, String name, String alias, String desc) {
            this.kind = kind;
            this.name = name;
            this.alias = alias;
            this.desc = desc;
        }
    }

    /** Qt：SoulFragment —— 割舍的声音（眉骨 → 下颌，逆向释放声灵收集） */
    public static class SoulFragment {
        public final int id;
        public final String name, source;
        public boolean collected;

        SoulFragment(int id, String name) {
            this.id = id;
            this.name = name;
            this.source = "割舍的声音";
        }
    }

    /** Qt：initSpirits() —— 6 只声灵（逐字） */
    private void initSpirits() {
        mSpirits.clear();
        mSpirits.add(new SpiritInfo(SpiritKind.PRIME_ECHO, "原初嗡鸣", "万声之母", "它是起源，也是审判者。"));
        mSpirits.add(new SpiritInfo(SpiritKind.MIRROR, "镜声灵", "反射之灵", "它渴求真相，哪怕真相刺穿你。"));
        mSpirits.add(new SpiritInfo(SpiritKind.SILENCE, "默声灵", "消除之灵", "它渴望寂静，代价是你的记忆。"));
        mSpirits.add(new SpiritInfo(SpiritKind.LIE, "谎声灵", "伪造之灵", "它以谎言为食，也会用谎言喂养你。"));
        mSpirits.add(new SpiritInfo(SpiritKind.SHATTERED, "碎璃声灵", "背叛之灵", "它附身于背叛者的脚步声。"));
        mSpirits.add(new SpiritInfo(SpiritKind.WRATH, "愤怒声灵", "暴怒之灵", "它煽动你的怒火，再从中取暖。"));
    }

    /** Qt：initSoulFragments() —— 6 块灵魂碎片 */
    private void initSoulFragments() {
        mSoulFragments.clear();
        String[] fragNames = {"眉骨", "左眼", "右眼", "鼻梁", "唇角", "下颌"};
        for (int i = 0; i < fragNames.length; i++) {
            mSoulFragments.add(new SoulFragment(i, fragNames[i]));
        }
    }

    public java.util.List<SpiritInfo> spirits() {
        return mSpirits;
    }

    private SpiritInfo findSpirit(SpiritKind kind) {
        for (SpiritInfo s : mSpirits) {
            if (s.kind == kind) return s;
        }
        return null;
    }

    /** Qt：fragmentSpirit() —— 碎片类型 → 寄宿声灵（每种声音都对应一位声灵，拼图即唤醒） */
    public SpiritKind fragmentSpirit(FragmentType type) {
        switch (type) {
            case FOOTSTEP:        return SpiritKind.SHATTERED; // 背叛者的脚步
            case DOOR_CREAK:      return SpiritKind.SHATTERED; // 被推开的秘密之门
            case ELECTRIC_BUZZ:   return SpiritKind.PRIME_ECHO; // 电流嗡鸣
            case STATIC_NOISE:    return SpiritKind.PRIME_ECHO; // 白噪声
            case LOW_FREQUENCY:   return SpiritKind.PRIME_ECHO; // 低频嗡鸣
            case GEOMAGNETIC:     return SpiritKind.PRIME_ECHO; // 地磁波动
            case WATER_DROP:      return SpiritKind.MIRROR;    // 滴水反射
            case RAIN_AMBIENT:    return SpiritKind.MIRROR;    // 雨声倒映
            case CLOCK_TICK:      return SpiritKind.MIRROR;    // 时钟的规律回声
            case HEARTBEAT:       return SpiritKind.SILENCE;   // 心跳与静默
            case BREATH:          return SpiritKind.SILENCE;   // 呼吸渐止
            case VOICE_WHISPER:   return SpiritKind.LIE;       // 无法辨认的低语
            case TYPEWRITER:      return SpiritKind.LIE;       // 打出来的都是伪证
            case TIRE_SCREECH:    return SpiritKind.WRATH;     // 刺耳急刹
            case METAL_EXPANSION: return SpiritKind.WRATH;     // 金属怒吼
            default:              return SpiritKind.PRIME_ECHO;
        }
    }

    /** Qt：summonSpirit() —— 放置碎片即召唤寄宿声灵（好感度 +0.06） */
    public void summonSpirit(SpiritKind kind) {
        SpiritInfo s = findSpirit(kind);
        if (s == null) return;
        s.affinity = Math.min(1f, s.affinity + 0.06f);
        // 好感度达标后显形
        if (s.affinity >= 0.5f && !s.manifested) {
            s.manifested = true;
        }
        // 声灵反噬：过度依赖（好感度 ≥ 0.85）——每轮记录一次 Qt 反噬文案
        if (s.affinity >= 0.85f && !s.backlashNotified) {
            s.backlashNotified = true;
            logSpirit(spiritBacklashText(kind));
        }
    }

    public float spiritAffinity(SpiritKind kind) {
        SpiritInfo s = findSpirit(kind);
        return s == null ? 0f : s.affinity;
    }

    public boolean isSpiritBacklashing(SpiritKind kind) {
        return spiritAffinity(kind) >= 0.85f;
    }

    public boolean isSpiritManifested(SpiritKind kind) {
        SpiritInfo s = findSpirit(kind);
        return s != null && s.manifested;
    }

    /** Qt：spiritBacklashText() —— 声灵反噬文案（逐字） */
    public String spiritBacklashText(SpiritKind kind) {
        switch (kind) {
            case LIE:
                return "谎声灵开始主动润色你的拼图——声音越来越顺耳，但真相被悄悄篡改了。";
            case WRATH:
                return "愤怒声灵让你的时间轴染上血红色——它正把你推向它想要的结局。";
            case MIRROR:
                return "镜声灵映照出你不敢面对的真相——每一滴水声都在逼你直视自己。";
            case SILENCE:
                return "默声灵吞掉了拼图里的杂音——你发现自己的某段记忆被它吃掉了。";
            case SHATTERED:
                return "碎璃声灵在脚步声里低语——它说，背叛者早已站在你身后。";
            case PRIME_ECHO:
                return "原初嗡鸣开始审判——所有你拼出的声音，都在质问你到底是谁。";
            default:
                return "";
        }
    }

    /** Qt：accumulateDeviation() —— 偏差能量累积，过载即原初嗡鸣倒计时触发 */
    public void accumulateDeviation(float delta) {
        mDeviation = Math.min(1f, mDeviation + delta);
        if (mDeviation >= 1f && !mDeviationOverloaded) {
            mDeviationOverloaded = true;
        }
    }

    public float deviation() {
        return mDeviation;
    }

    public boolean isPrimeEchoOverloaded() {
        return mDeviationOverloaded;
    }

    public int collectedSoulFragmentCount() {
        int c = 0;
        for (SoulFragment f : mSoulFragments) {
            if (f.collected) c++;
        }
        return c;
    }

    public boolean allSoulFragmentsCollected() {
        return collectedSoulFragmentCount() == mSoulFragments.size();
    }

    /** Qt：releaseSpirit() —— 释放声灵回归原初嗡鸣（逆向割舍声音），记录献祭并收集灵魂碎片 */
    public String releaseSpirit(SpiritKind kind) {
        SpiritInfo s = findSpirit(kind);
        if (s == null) return "";
        String name = s.name;
        s.manifested = false;
        s.affinity = 0f;
        s.backlashNotified = false;
        mSpiritReleaseCount++;
        accumulateDeviation(0.1f);
        int idx = mSpiritReleaseCount - 1;
        if (idx >= 0 && idx < mSoulFragments.size()) {
            mSoulFragments.get(idx).collected = true;
        }
        return "你释放了「" + name + "」，它回归了原初嗡鸣。";
    }

    /** 本局声灵回响（释放 / 反噬等 Qt 文案），在章节结算页集中展示 */
    public java.util.List<String> spiritLog() {
        return new java.util.ArrayList<>(mSpiritLog);
    }

    private void logSpirit(String text) {
        if (text == null || text.isEmpty()) return;
        mSpiritLog.add(text);
        while (mSpiritLog.size() > 6) mSpiritLog.remove(0);
    }

    /** Qt：静音惩罚 */
    public float silenceTendency() {
        return mSilenceTendency;
    }

    public boolean isRightEarSilenced() {
        return mRightEarSilenced;
    }
}
