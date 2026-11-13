package com.limelight.shell.screens

/**
 * Hiragana readings of the kanji words in the Japanese setting titles, section and tab names and search aliases, so that the
 * 46 hiragana keys of the letter picker find them (see [SearchText]). The key is a run of kanji as the titles write it, the value
 * its reading without the okurigana that follows in the text (`戻す` is `戻` = `もど` + す). This is data, not interface text:
 * `SettingsSearchTextTest` fails when a title gets a kanji that no entry covers, which is the moment to add the word here.
 */
internal object JapaneseReadings {
    private const val MAX_WORD = 6

    fun read(text: String): String {
        val out = StringBuilder(text.length * 2)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (!isHan(ch)) { out.append(ch); i++; continue }
            var n = minOf(MAX_WORD, text.length - i)
            while (n > 0 && WORDS[text.substring(i, i + n)] == null) n--
            if (n == 0) { out.append(ch); i++ } else { out.append(WORDS.getValue(text.substring(i, i + n))); i += n }
        }
        return out.toString()
    }

    fun isHan(ch: Char) = ch in '\u4E00'..'\u9FFF' || ch == '々'

    /** Every kanji of [text] that no entry starts a word with. */
    fun uncovered(text: String): Set<Char> {
        val unread = read(text)
        return unread.filter { isHan(it) }.toSet()
    }

    private val WORDS: Map<String, String> = """
        一般設定=いっぱんせってい 上=うえ 上書=うわが 上部中央=じょうぶちゅうおう 不足=ふそく 不透明度=ふとうめいど 中=ちゅう
        互換=ごかん 他=た 仮想=かそう 低下=ていか 使用=しよう 使用時=しようじ 係数=けいすう 保存=ほぞん 修飾=しゅうしょく
        入=い 入力=にゅうりょく 入力設定=にゅうりょくせってい 全体=ぜんたい 内容=ないよう 再=さい 再生=さいせい
        再表示=さいひょうじ 右=みぎ 合=あ 合成=ごうせい 同期=どうき 品質=ひんしつ 四角=しかく 回線=かいせん 報告=ほうこく
        境界=きょうかい 変更=へんこう 外観=がいかん 外部=がいぶ 完全外部=かんぜんがいぶ 対応=たいおう 常=つね 幅=はば
        座標=ざひょう 強=つよ 強制=きょうせい 従量制=じゅうりょうせい 情報=じょうほう 感度=かんど 戻=もど 技術=ぎじゅつ
        拡大率=かくだいりつ 拡大縮小=かくだいしゅくしょう 振動=しんどう 振動機能=しんどうきのう 推奨=すいしょう 操作=そうさ
        新=あたら 既定=きてい 時計=とけい 時間=じかん 替=か 有効=ゆうこう 構成=こうせい 消去=しょうきょ 済=ず 無効=むこう
        無効化=むこうか 無視=むし 特殊=とくしゅ 状態=じょうたい 用=よう 画面=がめん 画面上=がめんじょう
        画面中央=がめんちゅうおう 画面感度=がめんかんど 確定=かくてい 端末=たんまつ 端末内蔵=たんまつないぞう 管理=かんり
        統計=とうけい 背景=はいけい 自動=じどう 自動反転=じどうはんてん 自動検出=じどうけんしゅつ
        自動画面回転=じどうがめんかいてん 自動調整=じどうちょうせい 色=いろ 表示=ひょうじ 表示位置=ひょうじいち 観戦=かんせん
        解像度=かいぞうど 解放=かいほう 言語=げんご 記憶=きおく 設定=せってい 許可=きょか 診断=しんだん 詳細設定=しょうさいせってい
        調整=ちょうせい 警告=けいこく 起動=きどう 超=こ 超低遅延=ちょうていちえん 軸=じく 通常=つうじょう 進=すす 適用=てきよう
        選択=せんたく 配置=はいち 長押=ながお 開=ひら 開始=かいし 隠=かく 非同期=ひどうき 音=おん 音声=おんせい 高=たか
        安定化=あんていか 平滑化=へいかつか 帯域=たいいき 測定=そくてい 速度=そくど 接続=せつぞく 待機=たいき 暗=くら
    """.trim().split(Regex("\\s+")).associate { it.substringBefore('=') to it.substringAfter('=') }
}

/**
 * The pinyin initial of the Han characters of the Simplified Chinese titles, section and tab names and search aliases (the most
 * common reading where a character has several: 调 is `t`, 重 `c`, 长 `c`), so that the A-Z keys of the letter picker find them
 * (see [SearchText]). This is data, not interface text: `SettingsSearchTextTest` fails when a title gets a character that is not here.
 */
internal object PinyinInitials {
    fun of(ch: Char): Char? = BY_CHAR[ch]

    /** Every Han character of [text] that has no initial. */
    fun uncovered(text: String): Set<Char> = text.filter { it in '\u4E00'..'\u9FFF' && it !in BY_CHAR }.toSet()

    private val BY_CHAR: Map<Char, Char> = mapOf(
        'a' to "按案暗",
        'b' to "不保备布帮报播本板柄标步版编背辨边部",
        'c' to "串凑出存察常成持材查测程菜藏触超迟采重长除传",
        'd' to "低到动单地多定对导店度打断电的顶带",
        'f' to "分反否封放方翻范覆费副",
        'g' to "个光关告改更杆滚盖管观规过高感",
        'h' to "合后和回忽换欢活滑环画糊衡",
        'j' to "交仅件兼剪均基局居技据接景检激界监禁紧级节荐解警计记进键间降",
        'k' to "可宽开控看馈空库",
        'l' to "流灵率理留略类络螺路量链龙连线",
        'm' to "密幕摸敏明模码面默",
        'n' to "内拟能",
        'p' to "匹屏平普片盘配频",
        'q' to "全其前区启器强清签缺驱齐",
        'r' to "入容日绕认锐",
        's' to "上事使刷势商声失始式所手数时是术死殊生示素缩色视设试输适锁饰鼠速",
        't' to "他同图态拖推提特统调贴跳退透通陀题体",
        'w' to "为位围外完文网无稳",
        'x' to "下信修向型形心性息戏效新显消系虚行许选骁闲",
        'y' to "义于仪允元原右已延异摇应有样游用由移言语迎远钥隐音页运",
        'z' to "中主住作制助在坐奏帧志择指支整智桌滞状粘终置自诊质转轴载钟震",
    ).flatMap { (initial, chars) -> chars.map { it to initial } }.toMap()
}
