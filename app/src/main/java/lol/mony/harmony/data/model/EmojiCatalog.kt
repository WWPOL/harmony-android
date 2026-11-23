package lol.mony.harmony.data.model

/**
 * Every emoji this client can offer, as a table generated from Unicode's own data.
 *
 * The sources are `emoji-test.txt` (Unicode 16.0) for the emoji, their names and their order,
 * and CLDR's English annotations for the words people search by -- which is why "lol" finds
 * 😂 and "japan" finds the flag. Both are Unicode data files under the Unicode licence,
 * not another client's source: `scripts/emoji/gen_emoji.py` regenerates this file, and
 * `EmojiCatalogTest` checks what the table promises.
 *
 * Only fully-qualified *base* emoji are listed. A skin tone is applied when one is picked
 * ([EmojiEntry.withTone]), which is one table instead of six for the same set of glyphs: a base
 * is marked tone-able only when Unicode's own toned forms are exactly what that rule produces,
 * so the picker can never offer a tone that draws something else.
 *
 * Each block is one category's rows -- `glyph<TAB>name<TAB>keywords<TAB>tone-able` -- and they
 * are separate constants because the JVM caps one string constant at 65535 bytes.
 */


/** One emoji as the picker lists it. */
internal data class EmojiEntry(
    /** The glyphs to insert, already carrying any skin tone. */
    val glyph: String,
    /** Unicode's own name, in words a person can read and search. */
    val name: String,
    /** The words CLDR says people search this emoji by, minus those already in [name]. */
    val keywords: List<String>,
    val group: EmojiGroup,
    /** Whether [withTone] produces a real emoji for this one. */
    val toneAble: Boolean,
    /** The glyphs without a tone, which is what [withTone] modifies. */
    val base: String,
) {
    /** This emoji with a skin tone applied, or this emoji when it takes no tone. */
    fun withTone(tone: EmojiTone): EmojiEntry {
        if (!toneAble || tone == EmojiTone.Default) return this
        val points = base.codePoints().toArray()
        val toned = StringBuilder()
        var index = 0
        while (index < points.size) {
            val point = points[index]
            toned.appendCodePoint(point)
            index++
            if (point in EmojiTone.MODIFIER_BASES) {
                toned.appendCodePoint(tone.codepoint)
                // A variation selector before a tone is dropped, as Unicode spells the pair.
                if (index < points.size && points[index] == 0xFE0F) index++
            }
        }
        return copy(glyph = toned.toString())
    }

    /** The words this emoji is found by: its name and its keywords, for a screen reader. */
    val searchable: String get() = if (keywords.isEmpty()) name else name + " " + keywords.joinToString(" ")
}

/**
 * The categories the picker's tabs name, in the order Unicode lists them.
 *
 * The labels are the ones people know the groups by, not the data file's long headings, and
 * [icon] is the glyph the tab draws.
 */
internal enum class EmojiGroup(val label: String, val icon: String) {
    SMILEYS("Smileys", "😀"),
    PEOPLE("People", "👋"),
    NATURE("Nature", "🐾"),
    FOOD("Food", "🍔"),
    TRAVEL("Travel", "🚗"),
    ACTIVITIES("Activities", "⚽"),
    OBJECTS("Objects", "💡"),
    SYMBOLS("Symbols", "❤️"),
    FLAGS("Flags", "🏳️"),

    ;

    companion object {
        /** Every category, in tab order. */
        val tabs: List<EmojiGroup> get() = entries
    }
}

/** A skin tone, as Unicode's modifier codepoints name them. */
internal enum class EmojiTone(val label: String, val swatch: String, val codepoint: Int) {
    Default("Default", "👋", 0),
    Light("Light", "👋🏻", 0x1F3FB),
    MediumLight("Medium light", "👋🏼", 0x1F3FC),
    Medium("Medium", "👋🏽", 0x1F3FD),
    MediumDark("Medium dark", "👋🏾", 0x1F3FE),
    Dark("Dark", "👋🏿", 0x1F3FF),

    ;

    companion object {
        /**
         * The codepoints Unicode marks as emoji modifier bases: a tone follows one of these.
         *
         * It is the data file's own evidence -- every codepoint Unicode puts a tone after --
         * rather than a hand-kept list that could drift from the table above.
         */
        val MODIFIER_BASES: Set<Int> = setOf(
            0x261D, 0x26F9, 0x270A, 0x270B, 0x270C, 0x270D, 0x1F385, 0x1F3C2,
            0x1F3C3, 0x1F3C4, 0x1F3C7, 0x1F3CA, 0x1F3CB, 0x1F3CC, 0x1F442, 0x1F443,
            0x1F446, 0x1F447, 0x1F448, 0x1F449, 0x1F44A, 0x1F44B, 0x1F44C, 0x1F44D,
            0x1F44E, 0x1F44F, 0x1F450, 0x1F466, 0x1F467, 0x1F468, 0x1F469, 0x1F46B,
            0x1F46C, 0x1F46D, 0x1F46E, 0x1F470, 0x1F471, 0x1F472, 0x1F473, 0x1F474,
            0x1F475, 0x1F476, 0x1F477, 0x1F478, 0x1F47C, 0x1F481, 0x1F482, 0x1F483,
            0x1F485, 0x1F486, 0x1F487, 0x1F48F, 0x1F491, 0x1F4AA, 0x1F574, 0x1F575,
            0x1F57A, 0x1F590, 0x1F595, 0x1F596, 0x1F645, 0x1F646, 0x1F647, 0x1F64B,
            0x1F64C, 0x1F64D, 0x1F64E, 0x1F64F, 0x1F6A3, 0x1F6B4, 0x1F6B5, 0x1F6B6,
            0x1F6C0, 0x1F6CC, 0x1F90C, 0x1F90F, 0x1F918, 0x1F919, 0x1F91A, 0x1F91B,
            0x1F91C, 0x1F91D, 0x1F91E, 0x1F91F, 0x1F926, 0x1F930, 0x1F931, 0x1F932,
            0x1F933, 0x1F934, 0x1F935, 0x1F936, 0x1F937, 0x1F938, 0x1F939, 0x1F93D,
            0x1F93E, 0x1F977, 0x1F9B5, 0x1F9B6, 0x1F9B8, 0x1F9B9, 0x1F9BB, 0x1F9CD,
            0x1F9CE, 0x1F9CF, 0x1F9D1, 0x1F9D2, 0x1F9D3, 0x1F9D4, 0x1F9D5, 0x1F9D6,
            0x1F9D7, 0x1F9D8, 0x1F9D9, 0x1F9DA, 0x1F9DB, 0x1F9DC, 0x1F9DD, 0x1FAC3,
            0x1FAC4, 0x1FAC5, 0x1FAF0, 0x1FAF1, 0x1FAF2, 0x1FAF3, 0x1FAF4, 0x1FAF5,
            0x1FAF6, 0x1FAF7, 0x1FAF8,
        )

        /** Every tone, default first, in the order the picker's row shows them. */
        val all: List<EmojiTone> get() = entries
    }
}

/**
 * The table itself, parsed once.
 *
 * [all] is in Unicode's own order, which is the order the picker draws: the faces people reach
 * for first sit at the top of their category rather than scattered by codepoint.
 */
internal object EmojiCatalog {
    val all: List<EmojiEntry> by lazy { parse() }

    /** The emoji in [group], in Unicode's order. */
    fun byGroup(group: EmojiGroup): List<EmojiEntry> = all.filter { it.group == group }

    /**
     * The emoji a search names, best first.
     *
     * Every word of the query has to match, in the emoji's name or in one of CLDR's keywords,
     * and the best kind of match decides the rank:
     *
     *  1. the name is the words ("cat" finds :cat:)
     *  2. a word of the name is the word ("heart" finds :red heart:)
     *  3. a keyword is the word ("lol" finds :face with tears of joy:)
     *  4. the name starts with the word
     *  5. a keyword starts with the word
     *  6. the name contains the word ("japan" finds :flag: Japan:)
     *  7. a keyword contains the word
     *
     * Ties keep the catalog's own order, which is Unicode's -- the order people reach for
     * emoji in -- so a tie never reshuffles between keystrokes.
     */
    fun search(query: String): List<EmojiEntry> {
        val terms = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
        if (terms.isEmpty()) return all
        return all.withIndex().mapNotNull { (index, entry) ->
            val name = entry.name
            val keywords = entry.keywords
            var rank = 0
            for (term in terms) {
                val keyword = keywords.firstOrNull { it.contains(term) }
                if (!name.contains(term) && keyword == null) return@mapNotNull null
                rank += when {
                    name == term -> 0
                    name.split(' ').any { it == term } -> 1
                    keyword == term -> 2
                    name.startsWith(term) -> 3
                    keyword != null && keyword.startsWith(term) -> 4
                    name.contains(term) -> 5
                    else -> 6
                }
            }
            rank to index to entry
        }.sortedWith(compareBy<Pair<Pair<Int, Int>, EmojiEntry>> { it.first.first }.thenBy { it.first.second })
            .map { it.second }
    }

    /** The emoji whose glyphs are exactly [glyph], for drawing a recent pick back. */
    fun byGlyph(glyph: String): EmojiEntry? = index[glyph]

    private val index: Map<String, EmojiEntry> by lazy { all.associateBy { it.glyph } }

    private fun parse(): List<EmojiEntry> = buildList {
        for ((group, block) in BLOCKS) {
            for (line in block.split('\n')) {
                if (line.isEmpty()) continue
                val fields = line.split('\t')
                val glyph = fields[0]
                add(EmojiEntry(
                    glyph = glyph,
                    name = fields[1],
                    keywords = if (fields[2].isEmpty()) emptyList() else fields[2].split(','),
                    group = group,
                    toneAble = fields.size > 3 && fields[3] == "t",
                    base = glyph,
                ))
            }
        }
    }

    private val BLOCKS: List<Pair<EmojiGroup, String>> = listOf(
        EmojiGroup.SMILEYS to """
😀	grinning face	cheerful,cheery,happy,laugh,nice,smile,smiling,teeth
😃	grinning face with big eyes	awesome,happy,mouth,open,smile,smiling,teeth,yay
😄	grinning face with smiling eyes	happy,laugh,lol,mouth,open,smile
😁	beaming face with smiling eyes	grin,grinning,happy,nice,smile,teeth
😆	grinning squinting face	closed,eyes,haha,hahaha,happy,laugh,lol,mouth,open,rofl,smile,smiling
😅	grinning face with sweat	cold,dejected,excited,mouth,nervous,open,smile,smiling,stress,stressed
🤣	rolling on the floor laughing	crying,face,funny,haha,happy,hehe,hilarious,joy,lmao,lol,rofl,roflmao,tear
😂	face with tears of joy	crying,feels,funny,haha,happy,hehe,hilarious,laugh,lmao,lol,rofl,roflmao
🙂	slightly smiling face	happy,smile
🙃	upside-down face	hehe,smile
🫠	melting face	disappear,dissolve,embarrassed,haha,heat,hot,liquid,lol,sarcasm,sarcastic
😉	winking face	flirt,heartbreaker,sexy,slide,tease,winks
😊	smiling face with smiling eyes	blush,glad,satisfied,smile
😇	smiling face with halo	angel,angelic,angels,blessed,fairy,fairytale,fantasy,happy,innocent,peaceful,smile,spirit,tale
🥰	smiling face with hearts	3,adore,crush,ily,love,romance,smile,you
😍	smiling face with heart-eyes	143,bae,feels,hearts,ily,kisses,love,romance,romantic,smile,xoxo
🤩	star-struck	excited,eyes,face,grinning,smile,starry-eyed,wow
😘	face blowing a kiss	adorbs,bae,flirt,heart,ily,love,lover,miss,muah,romantic,smooch,xoxo,you
😗	kissing face	143,date,dating,flirt,ily,love,smooch,smooches,xoxo,you
☺️	smiling face	happy,outlined,relaxed,smile
😚	kissing face with closed eyes	143,bae,blush,date,dating,flirt,ily,kisses,smooches,xoxo
😙	kissing face with smiling eyes	143,closed,date,dating,flirt,ily,kisses,love,night,smile
🥲	smiling face with tear	glad,grateful,happy,joy,pain,proud,relieved,smile,smiley,touched
😋	face savoring food	delicious,eat,full,hungry,smile,smiling,tasty,um,yum,yummy
😛	face with tongue	awesome,cool,nice,party,stuck-out,sweet
😜	winking face with tongue	crazy,epic,eye,funny,joke,loopy,nutty,party,stuck-out,wacky,weirdo,yolo
🤪	zany face	crazy,eye,eyes,goofy,large,small
😝	squinting face with tongue	closed,eye,eyes,gross,horrible,omg,stuck-out,taste,whatever,yolo
🤑	money-mouth face	paid
🤗	smiling face with open hands	hug,hugging
🤭	face with hand over mouth	giggle,giggling,oops,realization,secret,shock,sudden,surprise,whoops
🫢	face with open eyes and hand over mouth	amazement,awe,disbelief,embarrass,gasp,omg,quiet,scared,shock,surprise
🫣	face with peeking eye	captivated,embarrass,hide,hiding,peep,scared,shy,stare
🤫	shushing face	quiet,shh
🤔	thinking face	chin,consider,hmm,ponder,pondering,wondering
🫡	saluting face	good,luck,ok,respect,salute,sir,troops,yes
🤐	zipper-mouth face	keep,quiet,secret,shut
🤨	face with raised eyebrow	disapproval,disbelief,distrust,emoji,hmm,mild,skeptic,skeptical,skepticism,surprise,what
😐	neutral face	awkward,blank,deadpan,expressionless,fine,jealous,meh,oh,shade,straight,unamused,unhappy,unimpressed,whatever
😑	expressionless face	awkward,dead,fine,inexpressive,jealous,meh,not,oh,omg,straight,uh,unhappy,unimpressed,whatever
😶	face without mouth	awkward,blank,expressionless,mouthless,mute,quiet,secret,silence,silent,speechless
🫥	dotted line face	depressed,disappear,hidden,hide,introvert,invisible,meh,whatever,wtv
😶‍🌫️	face in clouds	absentminded,fog,head
😏	smirking face	boss,dapper,flirt,homie,kidding,leer,shade,slick,sly,smug,snicker,suave,suspicious,swag
😒	unamused face	...,bored,fine,jealous,jel,jelly,pissed,smh,ugh,uhh,unhappy,weird,whatever
🙄	face with rolling eyes	eyeroll,shade,ugh,whatever
😬	grimacing face	awk,awkward,dentist,grimace,grinning,smile,smiling
😮‍💨	face exhaling	blow,blowing,exhale,exhausted,gasp,groan,relief,sigh,smiley,smoke,whisper,whistle
🤥	lying face	liar,lie,pinocchio
🫨	shaking face	crazy,daze,earthquake,omg,panic,shock,surprise,vibrate,whoa,wow
🙂‍↔️	head shaking horizontally	no,shake
🙂‍↕️	head shaking vertically	nod,yes
😌	relieved face	calm,peace,relief,zen
😔	pensive face	awful,bored,dejected,died,disappointed,losing,lost,sad,sucks
😪	sleepy face	crying,good,night,sad,sleeping,tired
🤤	drooling face	
😴	sleeping face	bed,bedtime,good,goodnight,nap,night,tired,whatever,yawn,zzz
🫩	face with bags under eyes	bored,exhausted,fatigued,late,sleepy,tired,weary
😷	face with medical mask	cold,dentist,dermatologist,doctor,dr,germs,medicine,sick
🤒	face with thermometer	ill,sick
🤕	face with head-bandage	hurt,injury,ouch
🤢	nauseated face	gross,nasty,sick,vomit
🤮	face vomiting	barf,ew,gross,puke,sick,spew,throw,up
🤧	sneezing face	fever,flu,gesundheit,sick,sneeze
🥵	hot face	dying,feverish,heat,panting,red-faced,stroke,sweating,tongue
🥶	cold face	blue,blue-faced,freezing,frostbite,icicles,subzero,teeth
🥴	woozy face	dizzy,drunk,eyes,intoxicated,mouth,tipsy,uneven,wavy
😵	face with crossed-out eyes	dead,dizzy,feels,knocked,sick,tired
😵‍💫	face with spiral eyes	confused,dizzy,hypnotized,omg,smiley,trouble,whoa,woah,woozy
🤯	exploding head	blown,explode,mind,mindblown,no,shocked,way
🤠	cowboy hat face	cowgirl
🥳	partying face	bday,birthday,celebrate,celebration,excited,happy,hat,hooray,horn
🥸	disguised face	eyebrow,glasses,incognito,moustache,mustache,nose,person,spy,tache,tash
😎	smiling face with sunglasses	awesome,beach,bright,bro,chilling,cool,rad,relaxed,shades,slay,smile,style,swag,win
🤓	nerd face	brainy,clever,expert,geek,gifted,glasses,intelligent,smart
🧐	face with monocle	classy,fancy,rich,stuffy,wealthy
😕	confused face	befuddled,confusing,dunno,frown,hm,meh,not,sad,sorry,sure
🫤	face with diagonal mouth	confused,confusion,disappointed,doubt,doubtful,frustrated,frustration,meh,skeptical,unsure,whatever,wtv
😟	worried face	anxious,butterflies,nerves,nervous,sad,stress,stressed,surprised,worry
🙁	slightly frowning face	sad
☹️	frowning face	sad
😮	face with open mouth	believe,forgot,omg,shocked,surprised,sympathy,unbelievable,unreal,whoa,wow,you
😯	hushed face	epic,omg,stunned,surprised,whoa,woah
😲	astonished face	cost,no,omg,shocked,totally,way
😳	flushed face	amazed,awkward,crazy,dazed,dead,disbelief,embarrassed,geez,heat,hot,impressed,jeez,what,wow
🥺	pleading face	begging,big,eyes,mercy,not,please,pretty,puppy,sad,why
🥹	face holding back tears	admiration,aww,cry,embarrassed,feelings,grateful,gratitude,joy,please,proud,resist,sad
😦	frowning face with open mouth	caught,guard,scared,scary,surprise,what,wow
😧	anguished face	forgot,scared,scary,stressed,surprise,unhappy,what,wow
😨	fearful face	afraid,anxious,blame,scared,worried
😰	anxious face with sweat	blue,cold,eek,mouth,nervous,open,rushed,scared,yikes
😥	sad but relieved face	anxious,call,close,complicated,disappointed,not,sweat,time,whew
😢	crying face	awful,feels,miss,sad,tear,triste,unhappy
😭	loudly crying face	bawling,sad,sob,tear,tears,unhappy
😱	face screaming in fear	epic,fearful,munch,scared,screamer,shocked,surprised,woah
😖	confounded face	annoyed,confused,cringe,distraught,feels,frustrated,mad,sad
😣	persevering face	concentrate,concentration,focus,headache,persevere
😞	disappointed face	awful,blame,dejected,fail,losing,sad,unhappy
😓	downcast face with sweat	close,cold,feels,headache,nervous,sad,scared,yikes
😩	weary face	crying,fail,feels,hungry,mad,nooo,sad,sleepy,tired,unhappy
😫	tired face	cost,feels,nap,sad,sneeze
🥱	yawning face	bedtime,bored,goodnight,nap,night,sleep,sleepy,tired,whatever,zzz
😤	face with steam from nose	anger,angry,feels,fume,fuming,furious,fury,mad,triumph,unhappy,won
😡	enraged face	anger,angry,feels,mad,maddening,pouting,red,shade,unhappy,upset
😠	angry face	anger,blame,feels,frustrated,mad,maddening,rage,shade,unhappy,upset
🤬	face with symbols on mouth	censor,cursing,cussing,mad,pissed,swearing
😈	smiling face with horns	demon,devil,evil,fairy,fairytale,fantasy,purple,shade,smile,tale
👿	angry face with horns	demon,devil,evil,fairy,fairytale,fantasy,imp,mischievous,purple,shade,tale
💀	skull	body,dead,death,face,fairy,fairytale,lmao,monster,tale,yolo
☠️	skull and crossbones	dead,death,face,monster
💩	pile of poo	bs,comic,doo,dung,face,fml,monster,poop,smelly,smh,stink,stinks,stinky,turd
🤡	clown face	
👹	ogre	creature,devil,face,fairy,fairytale,fantasy,mask,monster,scary,tale
👺	goblin	angry,creature,face,fairy,fairytale,fantasy,mask,mean,monster,tale
👻	ghost	boo,creature,excited,face,fairy,fairytale,fantasy,halloween,haunting,monster,scary,silly,tale
👽	alien	creature,extraterrestrial,face,fairy,fairytale,fantasy,monster,space,tale,ufo
👾	alien monster	creature,extraterrestrial,face,fairy,fairytale,fantasy,game,gamer,games,pixelated,space,tale,ufo
🤖	robot	face,monster
😺	grinning cat	animal,face,mouth,open,smile,smiling
😸	grinning cat with smiling eyes	animal,face,smile
😹	cat with tears of joy	animal,face,laugh,laughing,lol
😻	smiling cat with heart-eyes	animal,face,love,smile
😼	cat with wry smile	animal,face,ironic
😽	kissing cat	animal,closed,eye,eyes,face
🙀	weary cat	animal,face,oh,surprised
😿	crying cat	animal,face,sad,tear
😾	pouting cat	animal,face
🙈	see-no-evil monkey	embarrassed,face,forbidden,forgot,gesture,hide,omg,prohibited,scared,secret,smh,watch
🙉	hear-no-evil monkey	animal,ears,face,forbidden,gesture,listen,not,prohibited,secret,shh,tmi
🙊	speak-no-evil monkey	animal,face,forbidden,gesture,not,oops,prohibited,quiet,secret,stealth
💌	love letter	heart,mail,romance,valentine
💘	heart with arrow	143,adorbs,cupid,date,emotion,ily,love,romance,valentine
💝	heart with ribbon	143,anniversary,emotion,ily,kisses,valentine,xoxo
💖	sparkling heart	143,emotion,excited,good,ily,kisses,morning,night,sparkle,xoxo
💗	growing heart	143,emotion,excited,heartpulse,ily,kisses,muah,nervous,pulse,xoxo
💓	beating heart	143,cardio,emotion,heartbeat,ily,love,pulsating,pulse
💞	revolving hearts	143,adorbs,anniversary,emotion
💕	two hearts	143,anniversary,date,dating,emotion,ily,kisses,love,loving,xoxo
💟	heart decoration	143,emotion,hearth,purple,white
❣️	heart exclamation	bang,heavy,mark,punctuation
💔	broken heart	break,crushed,emotion,heartbroken,lonely,sad
❤️‍🔥	heart on fire	burn,love,lust,sacred
❤️‍🩹	mending heart	healthier,improving,recovering,recuperating,well
❤️	red heart	emotion,love
🩷	pink heart	143,adorable,cute,emotion,ily,like,love,special,sweet
🧡	orange heart	143
💛	yellow heart	143,cardiac,emotion,ily,love
💚	green heart	143,emotion,ily,love,romantic
💙	blue heart	143,emotion,ily,love,romance
🩵	light blue heart	143,cute,cyan,emotion,ily,like,love,sky,special,teal
💜	purple heart	143,bestest,emotion,ily,love
🤎	brown heart	143
🖤	black heart	evil,wicked
🩶	grey heart	143,emotion,gray,ily,love,silver,slate,special
🤍	white heart	143
💋	kiss mark	dating,emotion,heart,kissing,lips,romance,sexy
💯	hundred points	100,a+,agree,clearly,definitely,faithful,fleek,full,keep,perfect,score,true,truth,yup
💢	anger symbol	angry,comic,mad,upset
💥	collision	bomb,boom,collide,comic,explode
💫	dizzy	comic,shining,shooting,star,stars
💦	sweat droplets	comic,drip,drops,splashing,squirt,water,wet,work,workout
💨	dashing away	cloud,comic,fart,fast,go,gone,gotta,running,smoke
🕳️	hole	
💬	speech balloon	bubble,comic,dialog,message,sms,talk,text,typing
👁️‍🗨️	eye in speech bubble	balloon,witness
🗨️	left speech bubble	balloon,dialog
🗯️	right anger bubble	angry,balloon,mad
💭	thought balloon	bubble,cartoon,cloud,comic,daydream,decisions,dream,idea,invent,invention,realize,think,thoughts,wonder
💤	ZZZ	comic,good,goodnight,night,sleep,sleeping,sleepy,tired,zzz
""",
        EmojiGroup.PEOPLE to """
👋	waving hand	bye,cya,g2g,greetings,gtg,hello,hey,hi,later,outtie,ttfn,ttyl,wave,yo,you	t
🤚	raised back of hand	backhand	t
🖐️	hand with fingers splayed	raised,stop
✋	raised hand	5,five,high,stop	t
🖖	vulcan salute	finger,hand,hands	t
🫱	rightwards hand	handshake,hold,reach,shake	t
🫲	leftwards hand	handshake,hold,reach,shake	t
🫳	palm down hand	dismiss,drop,dropped,pick,shoo,up	t
🫴	palm up hand	beckon,catch,come,hold,know,lift,me,offer,tell	t
🫷	leftwards pushing hand	block,five,halt,high,hold,pause,refuse,slap,stop,wait	t
🫸	rightwards pushing hand	block,five,halt,high,hold,pause,refuse,slap,stop,wait	t
👌	OK hand	awesome,bet,dope,fleek,fosho,got,gotcha,legit,ok,okay,pinch,rad,sure,sweet,three	t
🤌	pinched fingers	gesture,hand,hold,huh,interrogation,patience,relax,sarcastic,ugh,what,zip	t
🤏	pinching hand	amount,bit,fingers,little,small,sort	t
✌️	victory hand	peace
🤞	crossed fingers	hand,luck	t
🫰	hand with index finger and thumb crossed	expensive,heart,love,money,snap	t
🤟	love-you gesture	fingers,hand,ily,three	t
🤘	sign of the horns	finger,hand,rock-on	t
🤙	call me hand	hang,loose,shaka	t
👈	backhand index pointing left	finger	t
👉	backhand index pointing right	finger	t
👆	backhand index pointing up	finger	t
🖕	middle finger	hand	t
👇	backhand index pointing down	finger	t
☝️	index pointing up	finger,hand,this
🫵	index pointing at the viewer	finger,hand,poke,you	t
👍	thumbs up	+1,good,hand,like,yes	t
👎	thumbs down	-1,bad,dislike,good,hand,no,nope	t
✊	raised fist	clenched,hand,punch,solidarity	t
👊	oncoming fist	absolutely,agree,boom,bro,bruh,bump,clenched,correct,hand,knuckle,pound,punch,rock,ttyl	t
🤛	left-facing fist	leftwards	t
🤜	right-facing fist	rightwards	t
👏	clapping hands	applause,approval,awesome,congrats,congratulations,excited,good,great,homie,job,nice,prayed,well,yay	t
🙌	raising hands	celebration,gesture,hooray,praise,raised	t
🫶	heart hands	love,you	t
👐	open hands	hug,jazz,swerve	t
🤲	palms up together	cupped,dua,hands,pray,prayer,wish	t
🤝	handshake	agreement,deal,meeting	t
🙏	folded hands	appreciate,ask,beg,blessed,bow,cmon,five,gesture,high,please,pray,thanks,thx	t
✍️	writing hand	write
💅	nail polish	bored,care,cosmetics,done,makeup,manicure,whatever	t
🤳	selfie	camera,phone	t
💪	flexed biceps	arm,beast,bench,bodybuilder,bro,curls,gains,gym,jacked,muscle,press,ripped,strong,weightlift	t
🦾	mechanical arm	accessibility,prosthetic
🦿	mechanical leg	accessibility,prosthetic
🦵	leg	bent,foot,kick,knee,limb	t
🦶	foot	ankle,feet,kick,stomp	t
👂	ear	body,ears,hear,hearing,listen,listening,sound	t
🦻	ear with hearing aid	accessibility,hard	t
👃	nose	body,noses,nosey,odor,smell,smells	t
🧠	brain	intelligent,smart
🫀	anatomical heart	beat,cardiology,heartbeat,organ,pulse,real,red
🫁	lungs	breath,breathe,exhalation,inhalation,organ,respiration
🦷	tooth	dentist,pearly,teeth,white
🦴	bone	bones,dog,skeleton,wishbone
👀	eyes	body,face,googly,look,looking,omg,peep,see,seeing
👁️	eye	1,body,one
👅	tongue	body,lick,slurp
👄	mouth	beauty,body,kiss,kissing,lips,lipstick
🫦	biting lip	anxious,bite,fear,flirt,flirting,kiss,lipstick,nervous,sexy,uncomfortable,worried,worry
👶	baby	babies,children,goo,infant,newborn,pregnant,young	t
🧒	child	bright-eyed,grandchild,kid,young,younger	t
👦	boy	bright-eyed,child,grandson,kid,son,young,younger	t
👧	girl	bright-eyed,child,daughter,granddaughter,kid,virgo,young,younger,zodiac	t
🧑	person	adult	t
👱	person: blond hair	blond-haired,human	t
👨	man	adult,bro	t
🧔	person: beard	bearded,whiskers	t
🧔‍♂️	man: beard	bearded,whiskers	t
🧔‍♀️	woman: beard	bearded,whiskers	t
👨‍🦰	man: red hair		t
👨‍🦱	man: curly hair		t
👨‍🦳	man: white hair		t
👨‍🦲	man: bald		t
👩	woman	adult,lady	t
👩‍🦰	woman: red hair		t
🧑‍🦰	person: red hair		t
👩‍🦱	woman: curly hair		t
🧑‍🦱	person: curly hair		t
👩‍🦳	woman: white hair		t
🧑‍🦳	person: white hair		t
👩‍🦲	woman: bald		t
🧑‍🦲	person: bald		t
👱‍♀️	woman: blond hair	blond-haired,blonde	t
👱‍♂️	man: blond hair	blond-haired	t
🧓	older person	adult,elderly,grandparent,wise	t
👴	old man	adult,bald,elderly,gramps,grandfather,grandpa,wise	t
👵	old woman	adult,elderly,grandma,grandmother,granny,lady,wise	t
🙍	person frowning	annoyed,disappointed,disgruntled,disturbed,frustrated,gesture,irritated,upset	t
🙍‍♂️	man frowning	annoyed,disappointed,disgruntled,disturbed,frustrated,gesture,irritated,upset	t
🙍‍♀️	woman frowning	annoyed,disappointed,disgruntled,disturbed,frustrated,gesture,irritated,upset	t
🙎	person pouting	disappointed,downtrodden,frown,grimace,scowl,sulk,upset,whine	t
🙎‍♂️	man pouting	disappointed,downtrodden,frown,grimace,scowl,sulk,upset,whine	t
🙎‍♀️	woman pouting	disappointed,downtrodden,frown,grimace,scowl,sulk,upset,whine	t
🙅	person gesturing NO	forbidden,gesture,hand,no,not,prohibit	t
🙅‍♂️	man gesturing NO	forbidden,gesture,hand,no,not,prohibit	t
🙅‍♀️	woman gesturing NO	forbidden,gesture,hand,no,not,prohibit	t
🙆	person gesturing OK	exercise,gesture,hand,ok,omg	t
🙆‍♂️	man gesturing OK	exercise,gesture,hand,ok,omg	t
🙆‍♀️	woman gesturing OK	exercise,gesture,hand,ok,omg	t
💁	person tipping hand	fetch,flick,flip,gossip,sarcasm,sarcastic,sassy,seriously,whatever	t
💁‍♂️	man tipping hand	fetch,flick,flip,gossip,sarcasm,sarcastic,sassy,seriously,whatever	t
💁‍♀️	woman tipping hand	fetch,flick,flip,gossip,sarcasm,sarcastic,sassy,seriously,whatever	t
🙋	person raising hand	gesture,here,know,me,pick,question,raise	t
🙋‍♂️	man raising hand	gesture,here,know,me,pick,question,raise	t
🙋‍♀️	woman raising hand	gesture,here,know,me,pick,question,raise	t
🧏	deaf person	accessibility,ear,gesture,hear	t
🧏‍♂️	deaf man	accessibility,ear,gesture,hear	t
🧏‍♀️	deaf woman	accessibility,ear,gesture,hear	t
🙇	person bowing	apology,ask,beg,favor,forgive,gesture,meditate,meditation,pity,regret,sorry	t
🙇‍♂️	man bowing	apology,ask,beg,favor,forgive,gesture,meditate,meditation,pity,regret,sorry	t
🙇‍♀️	woman bowing	apology,ask,beg,favor,forgive,gesture,meditate,meditation,pity,regret,sorry	t
🤦	person facepalming	again,bewilder,disbelief,exasperation,no,not,oh,omg,shock,smh	t
🤦‍♂️	man facepalming	again,bewilder,disbelief,exasperation,no,not,oh,omg,shock,smh	t
🤦‍♀️	woman facepalming	again,bewilder,disbelief,exasperation,no,not,oh,omg,shock,smh	t
🤷	person shrugging	doubt,dunno,guess,idk,ignorance,indifference,knows,maybe,whatever,who	t
🤷‍♂️	man shrugging	doubt,dunno,guess,idk,ignorance,indifference,knows,maybe,whatever,who	t
🤷‍♀️	woman shrugging	doubt,dunno,guess,idk,ignorance,indifference,knows,maybe,whatever,who	t
🧑‍⚕️	health worker	doctor,healthcare,nurse,therapist	t
👨‍⚕️	man health worker	doctor,healthcare,nurse,therapist	t
👩‍⚕️	woman health worker	doctor,healthcare,nurse,therapist	t
🧑‍🎓	student	graduate	t
👨‍🎓	man student	graduate	t
👩‍🎓	woman student	graduate	t
🧑‍🏫	teacher	instructor,lecturer,professor	t
👨‍🏫	man teacher	instructor,lecturer,professor	t
👩‍🏫	woman teacher	instructor,lecturer,professor	t
🧑‍⚖️	judge	justice,law,scales	t
👨‍⚖️	man judge	justice,law,scales	t
👩‍⚖️	woman judge	justice,law,scales	t
🧑‍🌾	farmer	gardener,rancher	t
👨‍🌾	man farmer	gardener,rancher	t
👩‍🌾	woman farmer	gardener,rancher	t
🧑‍🍳	cook	chef	t
👨‍🍳	man cook	chef	t
👩‍🍳	woman cook	chef	t
🧑‍🔧	mechanic	electrician,plumber,tradesperson	t
👨‍🔧	man mechanic	electrician,plumber,tradesperson	t
👩‍🔧	woman mechanic	electrician,plumber,tradesperson	t
🧑‍🏭	factory worker	assembly,industrial	t
👨‍🏭	man factory worker	assembly,industrial	t
👩‍🏭	woman factory worker	assembly,industrial	t
🧑‍💼	office worker	architect,business,manager,white-collar	t
👨‍💼	man office worker	architect,business,manager,white-collar	t
👩‍💼	woman office worker	architect,business,manager,white-collar	t
🧑‍🔬	scientist	biologist,chemist,engineer,mathematician,physicist	t
👨‍🔬	man scientist	biologist,chemist,engineer,mathematician,physicist	t
👩‍🔬	woman scientist	biologist,chemist,engineer,mathematician,physicist	t
🧑‍💻	technologist	coder,computer,developer,inventor,software	t
👨‍💻	man technologist	coder,computer,developer,inventor,software	t
👩‍💻	woman technologist	coder,computer,developer,inventor,software	t
🧑‍🎤	singer	actor,entertainer,rock,rockstar,star	t
👨‍🎤	man singer	actor,entertainer,rock,rockstar,star	t
👩‍🎤	woman singer	actor,entertainer,rock,rockstar,star	t
🧑‍🎨	artist	palette	t
👨‍🎨	man artist	palette	t
👩‍🎨	woman artist	palette	t
🧑‍✈️	pilot	plane	t
👨‍✈️	man pilot	plane	t
👩‍✈️	woman pilot	plane	t
🧑‍🚀	astronaut	rocket,space	t
👨‍🚀	man astronaut	rocket,space	t
👩‍🚀	woman astronaut	rocket,space	t
🧑‍🚒	firefighter	firetruck	t
👨‍🚒	man firefighter	firetruck	t
👩‍🚒	woman firefighter	firetruck	t
👮	police officer	apprehend,arrest,citation,cop,law,over,pulled,undercover	t
👮‍♂️	man police officer	apprehend,arrest,citation,cop,law,over,pulled,undercover	t
👮‍♀️	woman police officer	apprehend,arrest,citation,cop,law,over,pulled,undercover	t
🕵️	detective	sleuth,spy
🕵️‍♂️	man detective	sleuth,spy
🕵️‍♀️	woman detective	sleuth,spy
💂	guard	buckingham,helmet,london,palace	t
💂‍♂️	man guard	buckingham,helmet,london,palace	t
💂‍♀️	woman guard	buckingham,helmet,london,palace	t
🥷	ninja	assassin,fight,fighter,hidden,person,secret,skills,sly,soldier,stealth,war	t
👷	construction worker	build,fix,hardhat,hat,man,person,rebuild,remodel,repair	t
👷‍♂️	man construction worker	build,fix,hardhat,hat,rebuild,remodel,repair	t
👷‍♀️	woman construction worker	build,fix,hardhat,hat,rebuild,remodel,repair	t
🫅	person with crown	monarch,noble,regal,royal,royalty	t
🤴	prince	crown,fairy,fairytale,fantasy,king,royal,royalty,tale	t
👸	princess	crown,fairy,fairytale,fantasy,queen,royal,royalty,tale	t
👳	person wearing turban		t
👳‍♂️	man wearing turban		t
👳‍♀️	woman wearing turban		t
👲	person with skullcap	chinese,gua,guapi,hat,mao,pi	t
🧕	woman with headscarf	bandana,hijab,kerchief,mantilla,tichel	t
🤵	person in tuxedo	formal,wedding	t
🤵‍♂️	man in tuxedo	formal,groom,wedding	t
🤵‍♀️	woman in tuxedo	formal,wedding	t
👰	person with veil	wedding	t
👰‍♂️	man with veil	wedding	t
👰‍♀️	woman with veil	bride,wedding	t
🤰	pregnant woman		t
🫃	pregnant man	belly,bloated,full,overeat	t
🫄	pregnant person	belly,bloated,full,overeat,stuffed	t
🤱	breast-feeding	baby,mom,mother,nursing,woman	t
👩‍🍼	woman feeding baby	mom,mother,nanny,newborn,nursing	t
👨‍🍼	man feeding baby	dad,father,nanny,newborn,nursing	t
🧑‍🍼	person feeding baby	nanny,newborn,nursing,parent	t
👼	baby angel	church,face,fairy,fairytale,fantasy,tale	t
🎅	Santa Claus	celebration,christmas,claus,fairy,fantasy,father,holiday,merry,santa,tale,xmas	t
🤶	Mrs. Claus	celebration,christmas,claus,fairy,fantasy,holiday,merry,mother,mrs,santa,tale,xmas	t
🧑‍🎄	Mx Claus	celebration,christmas,claus,fairy,fantasy,holiday,merry,mx,santa,tale,xmas	t
🦸	superhero	good,superpower	t
🦸‍♂️	man superhero	good,superpower	t
🦸‍♀️	woman superhero	good,heroine,superpower	t
🦹	supervillain	bad,criminal,evil,superpower	t
🦹‍♂️	man supervillain	bad,criminal,evil,superpower	t
🦹‍♀️	woman supervillain	bad,criminal,evil,superpower	t
🧙	mage	fantasy,magic,play,sorcerer,sorceress,sorcery,spell,summon,witch,wizard	t
🧙‍♂️	man mage	fantasy,magic,play,sorcerer,sorceress,sorcery,spell,summon,witch,wizard	t
🧙‍♀️	woman mage	fantasy,magic,play,sorcerer,sorceress,sorcery,spell,summon,witch,wizard	t
🧚	fairy	fairytale,fantasy,myth,person,pixie,tale,wings	t
🧚‍♂️	man fairy	fairytale,fantasy,myth,oberon,person,pixie,puck,tale,wings	t
🧚‍♀️	woman fairy	fairytale,fantasy,myth,person,pixie,tale,titania,wings	t
🧛	vampire	blood,dracula,fangs,halloween,scary,supernatural,teeth,undead	t
🧛‍♂️	man vampire	blood,fangs,halloween,scary,supernatural,teeth,undead	t
🧛‍♀️	woman vampire	blood,fangs,halloween,scary,supernatural,teeth,undead	t
🧜	merperson	creature,fairytale,folklore,ocean,sea,siren,trident	t
🧜‍♂️	merman	creature,fairytale,folklore,neptune,ocean,poseidon,sea,siren,trident,triton	t
🧜‍♀️	mermaid	creature,fairytale,folklore,merwoman,ocean,sea,siren,trident	t
🧝	elf	elves,enchantment,fantasy,folklore,magic,magical,myth	t
🧝‍♂️	man elf	elves,enchantment,fantasy,folklore,magic,magical,myth	t
🧝‍♀️	woman elf	elves,enchantment,fantasy,folklore,magic,magical,myth	t
🧞	genie	djinn,fantasy,jinn,lamp,myth,rub,wishes
🧞‍♂️	man genie	djinn,fantasy,jinn,lamp,myth,rub,wishes
🧞‍♀️	woman genie	djinn,fantasy,jinn,lamp,myth,rub,wishes
🧟	zombie	apocalypse,dead,halloween,horror,scary,undead,walking
🧟‍♂️	man zombie	apocalypse,dead,halloween,horror,scary,undead,walking
🧟‍♀️	woman zombie	apocalypse,dead,halloween,horror,scary,undead,walking
🧌	troll	fairy,fantasy,monster,tale,trolling
💆	person getting massage	face,headache,relax,relaxing,salon,soothe,spa,tension,therapy,treatment	t
💆‍♂️	man getting massage	face,headache,relax,relaxing,salon,soothe,spa,tension,therapy,treatment	t
💆‍♀️	woman getting massage	face,headache,relax,relaxing,salon,soothe,spa,tension,therapy,treatment	t
💇	person getting haircut	barber,beauty,chop,cosmetology,groom,parlor,shears,style	t
💇‍♂️	man getting haircut	barber,beauty,chop,cosmetology,groom,parlor,person,shears,style	t
💇‍♀️	woman getting haircut	barber,beauty,chop,cosmetology,groom,parlor,person,shears,style	t
🚶	person walking	amble,gait,hike,man,pace,pedestrian,stride,stroll	t
🚶‍♂️	man walking	amble,gait,hike,pace,pedestrian,stride,stroll	t
🚶‍♀️	woman walking	amble,gait,hike,pace,pedestrian,stride,stroll	t
🚶‍➡️	person walking facing right		t
🚶‍♀️‍➡️	woman walking facing right		t
🚶‍♂️‍➡️	man walking facing right		t
🧍	person standing		t
🧍‍♂️	man standing		t
🧍‍♀️	woman standing		t
🧎	person kneeling	knees	t
🧎‍♂️	man kneeling	knees	t
🧎‍♀️	woman kneeling	knees	t
🧎‍➡️	person kneeling facing right		t
🧎‍♀️‍➡️	woman kneeling facing right		t
🧎‍♂️‍➡️	man kneeling facing right		t
🧑‍🦯	person with white cane	accessibility,blind,probing	t
🧑‍🦯‍➡️	person with white cane facing right		t
👨‍🦯	man with white cane	accessibility,blind,probing	t
👨‍🦯‍➡️	man with white cane facing right		t
👩‍🦯	woman with white cane	accessibility,blind,probing	t
👩‍🦯‍➡️	woman with white cane facing right		t
🧑‍🦼	person in motorized wheelchair	accessibility	t
🧑‍🦼‍➡️	person in motorized wheelchair facing right		t
👨‍🦼	man in motorized wheelchair	accessibility	t
👨‍🦼‍➡️	man in motorized wheelchair facing right		t
👩‍🦼	woman in motorized wheelchair	accessibility	t
👩‍🦼‍➡️	woman in motorized wheelchair facing right		t
🧑‍🦽	person in manual wheelchair	accessibility	t
🧑‍🦽‍➡️	person in manual wheelchair facing right		t
👨‍🦽	man in manual wheelchair	accessibility	t
👨‍🦽‍➡️	man in manual wheelchair facing right		t
👩‍🦽	woman in manual wheelchair	accessibility	t
👩‍🦽‍➡️	woman in manual wheelchair facing right		t
🏃	person running	fast,hurry,marathon,move,quick,race,racing,rush,speed	t
🏃‍♂️	man running	fast,hurry,marathon,move,quick,race,racing,rush,speed	t
🏃‍♀️	woman running	fast,hurry,marathon,move,quick,race,racing,rush,speed	t
🏃‍➡️	person running facing right		t
🏃‍♀️‍➡️	woman running facing right		t
🏃‍♂️‍➡️	man running facing right		t
💃	woman dancing	dance,dancer,elegant,festive,flair,flamenco,groove,salsa,tango	t
🕺	man dancing	dance,dancer,elegant,festive,flair,flamenco,groove,salsa,tango	t
🕴️	person in suit levitating	business
👯	people with bunny ears	bestie,bff,counterpart,dancer,double,identical,pair,party,partying,soulmate,twin,twinsies
👯‍♂️	men with bunny ears	bestie,bff,counterpart,dancer,double,identical,pair,party,partying,people,soulmate,twin,twinsies
👯‍♀️	women with bunny ears	bestie,bff,counterpart,dancer,double,identical,pair,party,partying,people,soulmate,twin,twinsies
🧖	person in steamy room	day,luxurious,pamper,relax,sauna,spa,steambath,unwind	t
🧖‍♂️	man in steamy room	day,luxurious,pamper,relax,sauna,spa,steambath,unwind	t
🧖‍♀️	woman in steamy room	day,luxurious,pamper,relax,sauna,spa,steambath,unwind	t
🧗	person climbing	climber,mountain,rock,scale,up	t
🧗‍♂️	man climbing	climber,mountain,rock,scale,up	t
🧗‍♀️	woman climbing	climber,mountain,rock,scale,up	t
🤺	person fencing	fencer,sword
🏇	horse racing	jockey,racehorse,riding,sport	t
⛷️	skier	snow
🏂	snowboarder	ski,sport	t
🏌️	person golfing	ball,birdie,caddy,driving,green,pga,putt,range,tee
🏌️‍♂️	man golfing	ball,birdie,caddy,driving,green,pga,putt,range,tee
🏌️‍♀️	woman golfing	ball,birdie,caddy,driving,green,pga,putt,range,tee
🏄	person surfing	beach,ocean,sport,surfer,swell,waves	t
🏄‍♂️	man surfing	beach,ocean,sport,surfer,swell,waves	t
🏄‍♀️	woman surfing	beach,ocean,person,sport,surfer,swell,waves	t
🚣	person rowing boat	canoe,cruise,fishing,lake,oar,paddle,raft,river,rowboat	t
🚣‍♂️	man rowing boat	canoe,cruise,fishing,lake,oar,paddle,raft,river,rowboat	t
🚣‍♀️	woman rowing boat	canoe,cruise,fishing,lake,oar,paddle,raft,river,rowboat	t
🏊	person swimming	freestyle,sport,swimmer,triathlon	t
🏊‍♂️	man swimming	freestyle,sport,swimmer,triathlon	t
🏊‍♀️	woman swimming	freestyle,sport,swimmer,triathlon	t
⛹️	person bouncing ball	athletic,basketball,championship,dribble,net,player,throw
⛹️‍♂️	man bouncing ball	athletic,basketball,championship,dribble,net,player,throw
⛹️‍♀️	woman bouncing ball	athletic,basketball,championship,dribble,net,player,throw
🏋️	person lifting weights	barbell,bodybuilder,deadlift,lifter,powerlifting,weightlifter,workout
🏋️‍♂️	man lifting weights	barbell,bodybuilder,deadlift,lifter,powerlifting,weightlifter,workout
🏋️‍♀️	woman lifting weights	barbell,bodybuilder,deadlift,lifter,powerlifting,weightlifter,workout
🚴	person biking	bicycle,bicyclist,bike,cycle,cyclist,riding,sport	t
🚴‍♂️	man biking	bicycle,bicyclist,bike,cycle,cyclist,riding,sport	t
🚴‍♀️	woman biking	bicycle,bicyclist,bike,cycle,cyclist,riding,sport	t
🚵	person mountain biking	bicycle,bicyclist,bike,cycle,cyclist,riding,sport	t
🚵‍♂️	man mountain biking	bicycle,bicyclist,bike,cycle,cyclist,riding,sport	t
🚵‍♀️	woman mountain biking	bicycle,bicyclist,bike,cycle,cyclist,riding,sport	t
🤸	person cartwheeling	active,excited,flip,gymnastics,happy,somersault	t
🤸‍♂️	man cartwheeling	active,excited,flip,gymnastics,happy,somersault	t
🤸‍♀️	woman cartwheeling	active,excited,flip,gymnastics,happy,somersault	t
🤼	people wrestling	combat,duel,grapple,ring,tournament,wrestle
🤼‍♂️	men wrestling	combat,duel,grapple,ring,tournament,wrestle
🤼‍♀️	women wrestling	combat,duel,grapple,ring,tournament,wrestle
🤽	person playing water polo	sport,swimming,waterpolo	t
🤽‍♂️	man playing water polo	sport,swimming,waterpolo	t
🤽‍♀️	woman playing water polo	sport,swimming,waterpolo	t
🤾	person playing handball	athletics,catch,chuck,hurl,lob,pitch,sport,throw,toss	t
🤾‍♂️	man playing handball	athletics,catch,chuck,hurl,lob,pitch,sport,throw,toss	t
🤾‍♀️	woman playing handball	athletics,catch,chuck,hurl,lob,pitch,sport,throw,toss	t
🤹	person juggling	act,balance,balancing,handle,juggle,manage,multitask,skill	t
🤹‍♂️	man juggling	act,balance,balancing,handle,juggle,manage,multitask,skill	t
🤹‍♀️	woman juggling	act,balance,balancing,handle,juggle,manage,multitask,skill	t
🧘	person in lotus position	cross,legged,legs,meditation,peace,relax,serenity,yoga,yogi,zen	t
🧘‍♂️	man in lotus position	cross,legged,legs,meditation,peace,relax,serenity,yoga,yogi,zen	t
🧘‍♀️	woman in lotus position	cross,legged,legs,meditation,peace,relax,serenity,yoga,yogi,zen	t
🛀	person taking bath	bathtub,tub	t
🛌	person in bed	bedtime,good,goodnight,hotel,nap,night,sleep,tired,zzz	t
🧑‍🤝‍🧑	people holding hands	bae,bestie,bff,couple,dating,flirt,friends,twins
👭	women holding hands	bae,bestie,bff,couple,dating,flirt,friends,girls,sisters,twins	t
👫	woman and man holding hands	bae,bestie,bff,couple,dating,flirt,friends,twins	t
👬	men holding hands	bae,bestie,bff,boys,brothers,couple,dating,flirt,friends,twins	t
💏	kiss	anniversary,babe,bae,couple,date,dating,heart,love,mwah,person,romance,together,xoxo	t
👩‍❤️‍💋‍👨	kiss: woman, man	
👨‍❤️‍💋‍👨	kiss: man, man	
👩‍❤️‍💋‍👩	kiss: woman, woman	
💑	couple with heart	anniversary,babe,bae,dating,kiss,love,person,relationship,romance,together,you	t
👩‍❤️‍👨	couple with heart: woman, man	
👨‍❤️‍👨	couple with heart: man, man	
👩‍❤️‍👩	couple with heart: woman, woman	
👨‍👩‍👦	family: man, woman, boy	
👨‍👩‍👧	family: man, woman, girl	
👨‍👩‍👧‍👦	family: man, woman, girl, boy	
👨‍👩‍👦‍👦	family: man, woman, boy, boy	
👨‍👩‍👧‍👧	family: man, woman, girl, girl	
👨‍👨‍👦	family: man, man, boy	
👨‍👨‍👧	family: man, man, girl	
👨‍👨‍👧‍👦	family: man, man, girl, boy	
👨‍👨‍👦‍👦	family: man, man, boy, boy	
👨‍👨‍👧‍👧	family: man, man, girl, girl	
👩‍👩‍👦	family: woman, woman, boy	
👩‍👩‍👧	family: woman, woman, girl	
👩‍👩‍👧‍👦	family: woman, woman, girl, boy	
👩‍👩‍👦‍👦	family: woman, woman, boy, boy	
👩‍👩‍👧‍👧	family: woman, woman, girl, girl	
👨‍👦	family: man, boy	
👨‍👦‍👦	family: man, boy, boy	
👨‍👧	family: man, girl	
👨‍👧‍👦	family: man, girl, boy	
👨‍👧‍👧	family: man, girl, girl	
👩‍👦	family: woman, boy	
👩‍👦‍👦	family: woman, boy, boy	
👩‍👧	family: woman, girl	
👩‍👧‍👦	family: woman, girl, boy	
👩‍👧‍👧	family: woman, girl, girl	
🗣️	speaking head	face,silhouette
👤	bust in silhouette	mysterious,shadow
👥	busts in silhouette	bff,everyone,friend,friends,people
🫂	people hugging	comfort,embrace,farewell,friendship,goodbye,hello,love,thanks
👪	family	child
🧑‍🧑‍🧒	family: adult, adult, child	
🧑‍🧑‍🧒‍🧒	family: adult, adult, child, child	
🧑‍🧒	family: adult, child	
🧑‍🧒‍🧒	family: adult, child, child	
👣	footprints	barefoot,clothing,omw,walk
🫆	fingerprint	clue,crime,detective,forensics,identity,mystery,safety,trace
""",
        EmojiGroup.NATURE to """
🐵	monkey face	animal,banana
🐒	monkey	animal,banana
🦍	gorilla	animal
🦧	orangutan	animal,ape,monkey
🐶	dog face	adorbs,animal,pet,puppies,puppy
🐕	dog	animal,animals,dogs,pet
🦮	guide dog	accessibility,animal,blind
🐕‍🦺	service dog	accessibility,animal,assistance
🐩	poodle	animal,dog,fluffy
🐺	wolf	animal,face
🦊	fox	animal,face
🦝	raccoon	animal,curious,sly
🐱	cat face	animal,kitten,kitty,pet
🐈	cat	animal,animals,cats,kitten,pet
🐈‍⬛	black cat	animal,feline,halloween,meow,unlucky
🦁	lion	alpha,animal,face,leo,mane,order,rawr,roar,safari,strong,zodiac
🐯	tiger face	animal,big,cat,predator
🐅	tiger	animal,big,cat,predator,zoo
🐆	leopard	animal,big,cat,predator,zoo
🐴	horse face	animal,dressage,equine,farm,horses
🫎	moose	alces,animal,antlers,elk,mammal
🫏	donkey	animal,ass,burro,hinny,mammal,mule,stubborn
🐎	horse	animal,equestrian,farm,racehorse,racing
🦄	unicorn	face
🦓	zebra	animal,stripe
🦌	deer	animal
🦬	bison	animal,buffalo,herd,wisent
🐮	cow face	animal,farm,milk,moo
🐂	ox	animal,animals,bull,farm,taurus,zodiac
🐃	water buffalo	animal,zoo
🐄	cow	animal,animals,farm,milk,moo
🐷	pig face	animal,bacon,farm,pork
🐖	pig	animal,bacon,farm,pork,sow
🐗	boar	animal,pig
🐽	pig nose	animal,face,farm,smell,snout
🐏	ram	animal,aries,horns,male,sheep,zodiac,zoo
🐑	ewe	animal,baa,farm,female,fluffy,lamb,sheep,wool
🐐	goat	animal,capricorn,farm,milk,zodiac
🐪	camel	animal,desert,dromedary,hump,one
🐫	two-hump camel	animal,bactrian,desert
🦙	llama	alpaca,animal,guanaco,wool
🦒	giraffe	animal,spots
🐘	elephant	animal
🦣	mammoth	animal,extinction,large,tusk,wooly
🦏	rhinoceros	animal
🦛	hippopotamus	animal
🐭	mouse face	animal
🐁	mouse	animal,animals
🐀	rat	animal
🐹	hamster	animal,face,pet
🐰	rabbit face	animal,bunny,pet
🐇	rabbit	animal,bunny,pet
🐿️	chipmunk	animal,squirrel
🦫	beaver	animal,dam,teeth
🦔	hedgehog	animal,spiny
🦇	bat	animal,vampire
🐻	bear	animal,face,grizzly,growl,honey
🐻‍❄️	polar bear	animal,arctic,white
🐨	koala	animal,australia,bear,down,face,marsupial,under
🐼	panda	animal,bamboo,face
🦥	sloth	lazy,slow
🦦	otter	animal,fishing,playful
🦨	skunk	animal,stink
🦘	kangaroo	animal,joey,jump,marsupial
🦡	badger	animal,honey,pester
🐾	paw prints	feet,paws
🦃	turkey	bird,gobble,thanksgiving
🐔	chicken	animal,bird,ornithology
🐓	rooster	animal,bird,ornithology
🐣	hatching chick	animal,baby,bird,egg
🐤	baby chick	animal,bird,ornithology
🐥	front-facing baby chick	animal,bird,newborn,ornithology
🐦	bird	animal,ornithology
🐧	penguin	animal,antarctica,bird,ornithology
🕊️	dove	bird,fly,ornithology,peace
🦅	eagle	animal,bird,ornithology
🦆	duck	animal,bird,ornithology
🦢	swan	animal,bird,cygnet,duckling,ornithology,ugly
🦉	owl	animal,bird,ornithology,wise
🦤	dodo	animal,bird,extinction,large,ornithology
🪶	feather	bird,flight,light,plumage
🦩	flamingo	animal,bird,flamboyant,ornithology,tropical
🦚	peacock	animal,bird,colorful,ornithology,ostentatious,peahen,pretty,proud
🦜	parrot	animal,bird,ornithology,pirate,talk
🪽	wing	angelic,ascend,aviation,bird,fly,flying,heavenly,mythology,soar
🐦‍⬛	black bird	animal,beak,caw,corvid,crow,ornithology,raven,rook
🪿	goose	animal,bird,duck,flock,fowl,gaggle,gander,geese,honk,ornithology,silly
🐦‍🔥	phoenix	ascend,ascension,emerge,fantasy,firebird,glory,immortal,rebirth,reincarnation,reinvent,renewal,revival,revive,rise,transform
🐸	frog	animal,face
🐊	crocodile	animal,zoo
🐢	turtle	animal,terrapin,tortoise
🦎	lizard	animal,reptile
🐍	snake	animal,bearer,ophiuchus,serpent,zodiac
🐲	dragon face	animal,fairy,fairytale,tale
🐉	dragon	animal,fairy,fairytale,knights,tale
🦕	sauropod	brachiosaurus,brontosaurus,dinosaur,diplodocus
🦖	T-Rex	dinosaur,rex,t,t-rex,tyrannosaurus
🐳	spouting whale	animal,beach,face,ocean
🐋	whale	animal,beach,ocean
🐬	dolphin	animal,beach,flipper,ocean
🦭	seal	animal,lion,ocean
🐟	fish	animal,dinner,fishes,fishing,pisces,zodiac
🐠	tropical fish	animal,fishes
🐡	blowfish	animal
🦈	shark	animal,fish
🐙	octopus	animal,creature,ocean
🐚	spiral shell	animal,beach,conch,sea
🪸	coral	change,climate,ocean,reef,sea
🪼	jellyfish	animal,aquarium,burn,invertebrate,life,marine,ocean,ouch,plankton,sea,sting,stinger,tentacles
🦀	crab	cancer,zodiac
🦞	lobster	animal,bisque,claws,seafood
🦐	shrimp	food,shellfish,small
🦑	squid	animal,food,mollusk
🦪	oyster	diving,pearl
🐌	snail	animal,escargot,garden,nature,slug
🦋	butterfly	insect,pretty
🐛	bug	animal,garden,insect
🐜	ant	animal,garden,insect
🐝	honeybee	animal,bumblebee,insect,nature,spring
🪲	beetle	animal,bug,insect
🐞	lady beetle	animal,garden,insect,ladybird,ladybug,nature
🦗	cricket	animal,bug,grasshopper,insect,orthoptera
🪳	cockroach	animal,insect,pest
🕷️	spider	animal,insect
🕸️	spider web	
🦂	scorpion	scorpius,zodiac
🦟	mosquito	bite,disease,fever,insect,malaria,pest,virus
🪰	fly	animal,disease,insect,maggot,pest,rotting
🪱	worm	animal,annelid,earthworm,parasite
🦠	microbe	amoeba,bacteria,science,virus
💐	bouquet	anniversary,birthday,date,flower,love,plant,romance
🌸	cherry blossom	flower,plant,spring,springtime
💮	white flower	
🪷	lotus	beauty,buddhism,calm,flower,hinduism,peace,purity,serenity
🏵️	rosette	plant
🌹	rose	beauty,elegant,flower,love,plant,red,valentine
🥀	wilted flower	dying
🌺	hibiscus	flower,plant
🌻	sunflower	outdoors,plant
🌼	blossom	buttercup,dandelion,flower,plant
🌷	tulip	blossom,flower,growth,plant
🪻	hyacinth	bloom,bluebonnet,flower,indigo,lavender,lilac,lupine,plant,purple,shrub,snapdragon,spring,violet
🌱	seedling	plant,sapling,sprout,young
🪴	potted plant	decor,grow,house,nurturing
🌲	evergreen tree	christmas,forest,pine
🌳	deciduous tree	forest,green,habitat,shedding
🌴	palm tree	beach,plant,tropical
🌵	cactus	desert,drought,nature,plant
🌾	sheaf of rice	ear,grain,grains,plant
🌿	herb	leaf,plant
☘️	shamrock	irish,plant
🍀	four leaf clover	4,four-leaf,irish,lucky,plant
🍁	maple leaf	falling
🍂	fallen leaf	autumn,falling
🍃	leaf fluttering in wind	blow
🪹	empty nest	branch,home,nesting
🪺	nest with eggs	bird,branch,nesting
🍄	mushroom	fungus,toadstool
🪾	leafless tree	bare,barren,branches,dead,drought,trunk,winter,wood
""",
        EmojiGroup.FOOD to """
🍇	grapes	dionysus,fruit
🍈	melon	cantaloupe,fruit
🍉	watermelon	fruit
🍊	tangerine	c,citrus,fruit,nectarine,orange,vitamin
🍋	lemon	citrus,fruit,sour
🍋‍🟩	lime	acidity,citrus,cocktail,fruit,garnish,key,margarita,mojito,refreshing,salsa,sour,tangy,tequila,tropical,zest
🍌	banana	fruit,potassium
🍍	pineapple	colada,fruit,pina,tropical
🥭	mango	food,fruit,tropical
🍎	red apple	diet,food,fruit,health,ripe
🍏	green apple	fruit
🍐	pear	fruit
🍑	peach	fruit
🍒	cherries	berries,cherry,fruit,red
🍓	strawberry	fruit
🫐	blueberries	berry,bilberry,blueberry,food,fruit
🥝	kiwi fruit	food
🍅	tomato	food,fruit,vegetable
🫒	olive	food
🥥	coconut	colada,palm
🥑	avocado	food,fruit
🍆	eggplant	aubergine,vegetable
🥔	potato	food,vegetable
🥕	carrot	food,vegetable
🌽	ear of corn	crops,farm,maize,maze
🌶️	hot pepper	
🫑	bell pepper	capsicum,food,vegetable
🥒	cucumber	food,pickle,vegetable
🥬	leafy green	bok,burgers,cabbage,choy,kale,lettuce,salad
🥦	broccoli	cabbage,wild
🧄	garlic	flavoring
🧅	onion	flavoring
🥜	peanuts	food,vegetable
🫘	beans	food,kidney,legume,small
🌰	chestnut	almond,plant
🫚	ginger root	beer,health,herb,natural,spice
🫛	pea pod	beans,beanstalk,edamame,legume,soybean,vegetable,veggie
🍄‍🟫	brown mushroom	food,fungi,fungus,nature,pizza,portobello,shiitake,spore,sprout,toppings,truffle,vegetable,vegetarian,veggie
🫜	root vegetable	beet,food,garden,radish,salad,turnip,vegetarian
🍞	bread	carbs,food,grain,loaf,restaurant,toast,wheat
🥐	croissant	bread,breakfast,crescent,food,french,roll
🥖	baguette bread	food,french
🫓	flatbread	arepa,food,gordita,lavash,naan,pita
🥨	pretzel	convoluted,twisted
🥯	bagel	bakery,bread,breakfast,schmear
🥞	pancakes	breakfast,food,hotcake
🧇	waffle	breakfast,indecisive,iron
🧀	cheese wedge	
🍖	meat on bone	
🍗	poultry leg	bone,chicken,drumstick,hungry,turkey
🥩	cut of meat	chop,lambchop,porkchop,red,steak
🥓	bacon	breakfast,food,meat
🍔	hamburger	eat,fast,food,hungry
🍟	french fries	fast,food
🍕	pizza	cheese,food,hungry,pepperoni,slice
🌭	hot dog	frankfurter,hotdog,sausage
🥪	sandwich	bread
🌮	taco	mexican
🌯	burrito	mexican,wrap
🫔	tamale	food,mexican,pamonha,wrapped
🥙	stuffed flatbread	falafel,food,gyro,kebab
🧆	falafel	chickpea,meatball
🥚	egg	breakfast,food
🍳	cooking	breakfast,easy,egg,fry,frying,over,pan,restaurant,side,sunny,up
🥘	shallow pan of food	casserole,paella
🍲	pot of food	soup,stew
🫕	fondue	cheese,chocolate,food,melted,pot,ski
🥣	bowl with spoon	breakfast,cereal,congee,oatmeal,porridge
🥗	green salad	food
🍿	popcorn	movie
🧈	butter	dairy
🧂	salt	condiment,flavor,mad,salty,shaker,taste,upset
🥫	canned food	
🍱	bento box	food
🍘	rice cracker	food
🍙	rice ball	food,japanese
🍚	cooked rice	food
🍛	curry rice	food
🍜	steaming bowl	chopsticks,food,noodle,pho,ramen,soup
🍝	spaghetti	food,meatballs,pasta,restaurant
🍠	roasted sweet potato	food
🍢	oden	food,kebab,restaurant,seafood,skewer,stick
🍣	sushi	food
🍤	fried shrimp	prawn,tempura
🍥	fish cake with swirl	food,pastry,restaurant
🥮	moon cake	autumn,festival
🍡	dango	dessert,japanese,skewer,stick,sweet
🥟	dumpling	empanada,jiaozi,pierogi,potsticker
🥠	fortune cookie	prophecy
🥡	takeout box	chopsticks,delivery,food,oyster,pail
🍦	soft ice cream	dessert,food,icecream,restaurant,serve,sweet
🍧	shaved ice	dessert,restaurant,sweet
🍨	ice cream	dessert,food,restaurant,sweet
🍩	doughnut	breakfast,dessert,donut,food,sweet
🍪	cookie	chip,chocolate,dessert,sweet
🎂	birthday cake	bday,celebration,dessert,happy,pastry,sweet
🍰	shortcake	dessert,pastry,slice,sweet
🧁	cupcake	bakery,dessert,sprinkles,sugar,sweet,treat
🥧	pie	apple,filling,fruit,meat,pastry,pumpkin,slice
🍫	chocolate bar	candy,dessert,halloween,sweet,tooth
🍬	candy	cavities,dessert,halloween,restaurant,sweet,tooth,wrapper
🍭	lollipop	candy,dessert,food,restaurant,sweet
🍮	custard	dessert,pudding,sweet
🍯	honey pot	barrel,bear,food,honeypot,jar,sweet
🍼	baby bottle	babies,birth,born,drink,infant,milk,newborn
🥛	glass of milk	drink
☕	hot beverage	cafe,caffeine,chai,coffee,drink,morning,steaming,tea
🫖	teapot	brew,drink,food
🍵	teacup without handle	beverage,drink,oolong
🍶	sake	bar,beverage,bottle,cup,drink,restaurant
🍾	bottle with popping cork	bar,drink
🍷	wine glass	alcohol,bar,beverage,booze,club,drink,drinking,drinks,restaurant
🍸	cocktail glass	alcohol,bar,booze,club,drink,drinking,drinks,mad,martini,men
🍹	tropical drink	alcohol,bar,booze,club,cocktail,drinking,drinks,drunk,mai,party,tai,tropics
🍺	beer mug	alcohol,ale,bar,booze,drink,drinking,drinks,octoberfest,oktoberfest,pint,stein,summer
🍻	clinking beer mugs	alcohol,bar,booze,bottoms,cheers,drinking,drinks
🥂	clinking glasses	celebrate,drink
🥃	tumbler glass	liquor,scotch,shot,whiskey,whisky
🫗	pouring liquid	accident,drink,empty,glass,oops,spill,water
🥤	cup with straw	drink,juice,malt,soda,soft,water
🧋	bubble tea	boba,food,milk,pearl
🧃	beverage box	juice,straw,sweet
🧉	mate	drink
🧊	ice	cold,cube,iceberg
🥢	chopsticks	hashi,jeotgarak,kuaizi
🍽️	fork and knife with plate	cooking,dinner,eat
🍴	fork and knife	breakfast,breaky,cooking,cutlery,delicious,dinner,eat,feed,food,hungry,lunch,restaurant,yum,yummy
🥄	spoon	eat,tableware
🔪	kitchen knife	chef,cooking,hocho,tool,weapon
🫙	jar	condiment,container,empty,nothing,sauce,store
🏺	amphora	aquarius,cooking,drink,jug,tool,weapon,zodiac
""",
        EmojiGroup.TRAVEL to """
🌍	globe showing Europe-Africa	africa,earth,europe,europe-africa,world
🌎	globe showing Americas	americas,earth,world
🌏	globe showing Asia-Australia	asia,asia-australia,australia,earth,world
🌐	globe with meridians	earth,internet,web,world,worldwide
🗺️	world map	
🗾	map of Japan	japan
🧭	compass	direction,magnetic,navigation,orienteering
🏔️	snow-capped mountain	cold
⛰️	mountain	
🌋	volcano	eruption,mountain,nature
🗻	mount fuji	mountain,nature
🏕️	camping	
🏖️	beach with umbrella	
🏜️	desert	
🏝️	desert island	
🏞️	national park	
🏟️	stadium	
🏛️	classical building	
🏗️	building construction	crane
🧱	brick	bricks,clay,mortar,wall
🪨	rock	boulder,heavy,solid,stone,tough
🪵	wood	log,lumber,timber
🛖	hut	home,house,roundhouse,shelter,yurt
🏘️	houses	
🏚️	derelict house	home
🏠	house	building,country,heart,home,ranch,settle,simple,suburban,suburbia,where
🏡	house with garden	building,country,heart,home,ranch,settle,simple,suburban,suburbia,where
🏢	office building	city,cubical,job
🏣	Japanese post office	building,japanese
🏤	post office	building,european
🏥	hospital	building,doctor,medicine
🏦	bank	building
🏨	hotel	building
🏩	love hotel	building
🏪	convenience store	24,building,hours
🏫	school	building
🏬	department store	building
🏭	factory	building
🏯	Japanese castle	building,japanese
🏰	castle	building,european
💒	wedding	chapel,hitched,nuptials,romance
🗼	Tokyo tower	tokyo
🗽	Statue of Liberty	liberty,new,ny,nyc,statue,york
⛪	church	bless,chapel,christian,cross,religion
🕌	mosque	islam,masjid,muslim,religion
🛕	hindu temple	
🕍	synagogue	jew,jewish,judaism,religion,temple
⛩️	shinto shrine	religion
🕋	kaaba	hajj,islam,muslim,religion,umrah
⛲	fountain	
⛺	tent	camping
🌁	foggy	
🌃	night with stars	
🏙️	cityscape	
🌄	sunrise over mountains	morning
🌅	sunrise	morning,nature
🌆	cityscape at dusk	building,evening,landscape,sun,sunset
🌇	sunset	building,dusk
🌉	bridge at night	
♨️	hot springs	hotsprings,steaming
🎠	carousel horse	entertainment
🛝	playground slide	amusement,park,playing,sliding,theme
🎡	ferris wheel	amusement,park,theme
🎢	roller coaster	amusement,park,theme
💈	barber pole	cut,fresh,haircut,shave
🎪	circus tent	
🚂	locomotive	caboose,engine,railway,steam,train,trains,travel
🚃	railway car	electric,train,tram,travel,trolleybus
🚄	high-speed train	railway,shinkansen
🚅	bullet train	high-speed,nose,railway,shinkansen,speed,travel
🚆	train	arrived,choo,railway
🚇	metro	subway,travel
🚈	light rail	arrived,monorail,railway
🚉	station	railway,train
🚊	tram	trolleybus
🚝	monorail	vehicle
🚞	mountain railway	car,trip
🚋	tram car	bus,trolley,trolleybus
🚌	bus	school,vehicle
🚍	oncoming bus	cars
🚎	trolleybus	tram
🚐	minibus	drive,van,vehicle
🚑	ambulance	emergency,vehicle
🚒	fire engine	truck
🚓	police car	cops,patrol
🚔	oncoming police car	
🚕	taxi	cab,cabbie,car,drive,vehicle,yellow
🚖	oncoming taxi	cab,cabbie,cars,drove,hail,yellow
🚗	automobile	car,driving,vehicle
🚘	oncoming automobile	car,cars,drove,vehicle
🚙	sport utility vehicle	car,drive,recreational,sportutility
🛻	pickup truck	automobile,car,flatbed,pick-up,transportation
🚚	delivery truck	car,drive,vehicle
🚛	articulated lorry	car,drive,move,semi,truck,vehicle
🚜	tractor	vehicle
🏎️	racing car	zoom
🏍️	motorcycle	racing
🛵	motor scooter	
🦽	manual wheelchair	accessibility
🦼	motorized wheelchair	accessibility
🛺	auto rickshaw	tuk
🚲	bicycle	bike,class,cycling,cyclist,gang,ride,spin,spinning
🛴	kick scooter	
🛹	skateboard	skater,wheels
🛼	roller skate	blades,skates,sport
🚏	bus stop	busstop
🛣️	motorway	highway,road
🛤️	railway track	train
🛢️	oil drum	
⛽	fuel pump	diesel,fuelpump,gas,gasoline,station
🛞	wheel	car,circle,tire,turn,vehicle
🚨	police car light	alarm,alert,beacon,emergency,revolving,siren
🚥	horizontal traffic light	intersection,signal,stop,stoplight
🚦	vertical traffic light	drove,intersection,signal,stop,stoplight
🛑	stop sign	octagonal
🚧	construction	barrier
⚓	anchor	ship,tool
🛟	ring buoy	float,life,lifesaver,preserver,rescue,safety,save,saver,swim
⛵	sailboat	resort,sailing,sea,yacht
🛶	canoe	boat
🚤	speedboat	billionaire,lake,luxury,millionaire,summer,travel
🛳️	passenger ship	
⛴️	ferry	boat,passenger
🛥️	motor boat	motorboat
🚢	ship	boat,passenger,travel
✈️	airplane	aeroplane,fly,flying,jet,travel
🛩️	small airplane	aeroplane
🛫	airplane departure	aeroplane,check-in,departures
🛬	airplane arrival	aeroplane,arrivals,arriving,landing
🪂	parachute	hang-glide,parasail,skydive
💺	seat	chair
🚁	helicopter	roflcopter,travel,vehicle
🚟	suspension railway	
🚠	mountain cableway	gondola,lift,ski
🚡	aerial tramway	cable,car,gondola,ropeway
🛰️	satellite	space
🚀	rocket	launch,rockets,space,travel
🛸	flying saucer	aliens,extra,terrestrial,ufo
🛎️	bellhop bell	hotel
🧳	luggage	bag,packing,roller,suitcase,travel
⌛	hourglass done	sand,time,timer
⏳	hourglass not done	flowing,hours,sand,timer,waiting,yolo
⌚	watch	clock,time
⏰	alarm clock	hours,hrs,late,time,waiting
⏱️	stopwatch	clock,time
⏲️	timer clock	
🕰️	mantelpiece clock	time
🕛	twelve o’clock	12,time
🕧	twelve-thirty	12,30,clock,time
🕐	one o’clock	1,time
🕜	one-thirty	1,30,clock,time
🕑	two o’clock	2,time
🕝	two-thirty	2,30,clock,time
🕒	three o’clock	3,time
🕞	three-thirty	3,30,clock,time
🕓	four o’clock	4,time
🕟	four-thirty	30,4,clock,time
🕔	five o’clock	5,time
🕠	five-thirty	30,5,clock,time
🕕	six o’clock	6,time
🕡	six-thirty	30,6,clock
🕖	seven o’clock	0,7
🕢	seven-thirty	30,7,clock
🕗	eight o’clock	8,time
🕣	eight-thirty	30,8,clock,time
🕘	nine o’clock	9,time
🕤	nine-thirty	30,9,clock,time
🕙	ten o’clock	0,10
🕥	ten-thirty	10,30,clock,time
🕚	eleven o’clock	11,time
🕦	eleven-thirty	11,30,clock,time
🌑	new moon	dark,space
🌒	waxing crescent moon	dreams,space
🌓	first quarter moon	space
🌔	waxing gibbous moon	space
🌕	full moon	space
🌖	waning gibbous moon	space
🌗	last quarter moon	space
🌘	waning crescent moon	space
🌙	crescent moon	ramadan,space
🌚	new moon face	space
🌛	first quarter moon face	space
🌜	last quarter moon face	dreams
🌡️	thermometer	weather
☀️	sun	bright,rays,space,sunny,weather
🌝	full moon face	bright
🌞	sun with face	beach,bright,day,heat,shine,sunny,sunshine,weather
🪐	ringed planet	saturn,saturnine
⭐	star	astronomy,medium,stars,white
🌟	glowing star	glittery,night,shining,sparkle
🌠	shooting star	falling,night,space
🌌	milky way	space
☁️	cloud	weather
⛅	sun behind cloud	cloudy,weather
⛈️	cloud with lightning and rain	thunder,thunderstorm
🌤️	sun behind small cloud	weather
🌥️	sun behind large cloud	weather
🌦️	sun behind rain cloud	weather
🌧️	cloud with rain	weather
🌨️	cloud with snow	cold,weather
🌩️	cloud with lightning	weather
🌪️	tornado	cloud,weather,whirlwind
🌫️	fog	cloud,weather
🌬️	wind face	blow,cloud
🌀	cyclone	dizzy,hurricane,twister,typhoon,weather
🌈	rainbow	gay,genderqueer,glbt,glbtq,lesbian,lgbt,lgbtq,lgbtqia,nature,pride,queer,trans,transgender,weather
🌂	closed umbrella	clothing,rain
☂️	umbrella	clothing,rain
☔	umbrella with rain drops	clothing,weather
⛱️	umbrella on ground	rain,sun
⚡	high voltage	danger,electric,electricity,lightning,nature,thunder,thunderbolt,zap
❄️	snowflake	cold,weather
☃️	snowman	cold
⛄	snowman without snow	cold
☄️	comet	space
🔥	fire	af,burn,flame,hot,lit,litaf,tool
💧	droplet	cold,comic,nature,sad,sweat,tear,water,weather
🌊	water wave	nature,ocean,surf,surfer,surfing
""",
        EmojiGroup.ACTIVITIES to """
🎃	jack-o-lantern	celebration,halloween,pumpkin
🎄	Christmas tree	celebration,christmas
🎆	fireworks	boom,celebration,entertainment,yolo
🎇	sparkler	boom,celebration,fireworks
🧨	firecracker	dynamite,explosive,fireworks,light,pop,popping,spark
✨	sparkles	magic,star
🎈	balloon	birthday,celebrate,celebration
🎉	party popper	awesome,birthday,celebrate,celebration,excited,hooray,tada,woohoo
🎊	confetti ball	celebrate,celebration,party,woohoo
🎋	tanabata tree	banner,celebration,japanese
🎍	pine decoration	bamboo,celebration,japanese,plant
🎎	Japanese dolls	celebration,festival,japanese
🎏	carp streamer	celebration
🎐	wind chime	bell,celebration
🎑	moon viewing ceremony	celebration
🧧	red envelope	gift,good,lai,luck,money,see
🎀	ribbon	celebration
🎁	wrapped gift	birthday,bow,box,celebration,christmas,present,surprise
🎗️	reminder ribbon	celebration
🎟️	admission tickets	
🎫	ticket	admission,stub
🎖️	military medal	award,celebration
🏆	trophy	champion,champs,prize,slay,sport,victory,win,winning
🏅	sports medal	award,gold,winner
🥇	1st place medal	first,gold
🥈	2nd place medal	second,silver
🥉	3rd place medal	bronze,third
⚽	soccer ball	football,futbol,sport
⚾	baseball	sport
🥎	softball	glove,sports,underarm
🏀	basketball	hoop,sport
🏐	volleyball	game
🏈	american football	bowl,sport,super
🏉	rugby football	sport
🎾	tennis	ball,racquet,sport
🥏	flying disc	ultimate
🎳	bowling	ball,game,sport,strike
🏏	cricket game	ball,bat
🏑	field hockey	ball,game,stick
🏒	ice hockey	game,puck,stick
🥍	lacrosse	ball,goal,sports,stick
🏓	ping pong	ball,bat,game,paddle,pingpong,table,tennis
🏸	badminton	birdie,game,racquet,shuttlecock
🥊	boxing glove	
🥋	martial arts uniform	judo,karate,taekwondo
🥅	goal net	
⛳	flag in hole	golf,sport
⛸️	ice skate	skating
🎣	fishing pole	entertainment,sport
🤿	diving mask	scuba,snorkeling
🎽	running shirt	athletics,sash
🎿	skis	snow,sport
🛷	sled	luge,sledge,sleigh,snow,toboggan
🥌	curling stone	game,rock
🎯	bullseye	dart,direct,entertainment,game,hit,target
🪀	yo-yo	fluctuate,toy
🪁	kite	fly,soar
🔫	water pistol	gun,handgun,revolver,tool,weapon
🎱	pool 8 ball	8ball,billiard,eight,game
🔮	crystal ball	fairy,fairytale,fantasy,fortune,future,magic,tale,tool
🪄	magic wand	magician,witch,wizard
🎮	video game	controller,entertainment
🕹️	joystick	game,video,videogame
🎰	slot machine	casino,gamble,gambling,game,slots
🎲	game die	dice,entertainment
🧩	puzzle piece	clue,interlocking,jigsaw
🧸	teddy bear	plaything,plush,stuffed,toy
🪅	piñata	candy,celebrate,celebration,cinco,de,festive,mayo,party,pinada,pinata
🪩	mirror ball	dance,disco,glitter,party
🪆	nesting dolls	babooshka,baboushka,babushka,matryoshka,russia
♠️	spade suit	card,game
♥️	heart suit	card,emotion,game,hearts
♦️	diamond suit	card,game
♣️	club suit	card,clubs,game
♟️	chess pawn	dupe,expendable
🃏	joker	card,game,wildcard
🀄	mahjong red dragon	game
🎴	flower playing cards	game,japanese
🎭	performing arts	actor,actress,entertainment,mask,theater,theatre,thespian
🖼️	framed picture	art,museum,painting
🎨	artist palette	artsy,arty,colorful,creative,entertainment,museum,painter,painting
🧵	thread	needle,sewing,spool,string
🪡	sewing needle	embroidery,stitches,sutures,tailoring,thread
🧶	yarn	ball,crochet,knit
🪢	knot	cord,rope,tangled,tie,twine,twist
""",
        EmojiGroup.OBJECTS to """
👓	glasses	clothing,eye,eyeglasses,eyewear
🕶️	sunglasses	dark,eye,eyewear
🥽	goggles	dive,eye,protection,scuba,swimming,welding
🥼	lab coat	clothes,doctor,dr,experiment,jacket,scientist,white
🦺	safety vest	emergency
👔	necktie	clothing,employed,serious,shirt
👕	t-shirt	blue,casual,clothes,clothing,collar,dressed,shopping,tshirt,weekend
👖	jeans	blue,casual,clothes,clothing,denim,dressed,pants,shopping,trousers,weekend
🧣	scarf	bundle,cold,neck,up
🧤	gloves	hand
🧥	coat	brr,bundle,cold,jacket,up
🧦	socks	stocking
👗	dress	clothes,clothing,dressed,fancy,shopping
👘	kimono	clothing,comfortable
🥻	sari	clothing,dress
🩱	one-piece swimsuit	bathing
🩲	briefs	bathing,one-piece,suit,swimsuit,underwear
🩳	shorts	bathing,pants,suit,swimsuit,underwear
👙	bikini	bathing,beach,clothing,pool,suit,swim
👚	woman’s clothes	blouse,clothing,collar,dress,dressed,lady,shirt,shopping
🪭	folding hand fan	clack,clap,cool,cooling,dance,flirt,flutter,hot,shy
👛	purse	clothes,clothing,coin,dress,fancy,handbag,shopping
👜	handbag	clothes,clothing,dress,lady,purse,shopping
👝	clutch bag	clothes,clothing,dress,handbag,pouch,purse
🛍️	shopping bags	hotel
🎒	backpack	backpacking,bag,bookbag,education,rucksack,satchel,school
🩴	thong sandal	beach,flip,flop,sandals,shoe,thongs
👞	man’s shoe	brown,clothes,clothing,feet,foot,kick,shoes,shopping
👟	running shoe	athletic,clothes,clothing,fast,kick,shoes,shopping,sneaker,tennis
🥾	hiking boot	backpacking,brown,camping,outdoors,shoe
🥿	flat shoe	ballet,comfy,flats,slip-on,slipper
👠	high-heeled shoe	clothes,clothing,dress,fashion,heels,shoes,shopping,stiletto,woman
👡	woman’s sandal	clothing,shoe
🩰	ballet shoes	dance
👢	woman’s boot	clothes,clothing,dress,shoe,shoes,shopping
🪮	hair pick	afro,comb,groom
👑	crown	clothing,family,king,medieval,queen,royal,royalty,win
👒	woman’s hat	clothes,clothing,garden,hats,party
🎩	top hat	clothes,clothing,fancy,formal,magic,tophat
🎓	graduation cap	celebration,clothing,education,hat,scholar
🧢	billed cap	baseball,bent,dad,hat
🪖	military helmet	army,soldier,war,warrior
⛑️	rescue worker’s helmet	aid,cross,face,hat
📿	prayer beads	clothing,necklace,religion
💄	lipstick	cosmetics,date,makeup
💍	ring	diamond,engaged,engagement,married,romance,shiny,sparkling,wedding
💎	gem stone	diamond,engagement,jewel,money,romance,wedding
🔇	muted speaker	quiet,silent,sound
🔈	speaker low volume	soft,sound
🔉	speaker medium volume	sound
🔊	speaker high volume	loud,music,sound
📢	loudspeaker	address,communication,public,sound
📣	megaphone	cheering,sound
📯	postal horn	
🔔	bell	break,church,sound
🔕	bell with slash	forbidden,mute,no,not,prohibited,quiet,silent,sound
🎼	musical score	note
🎵	musical note	sound
🎶	musical notes	sound
🎙️	studio microphone	music
🎚️	level slider	music
🎛️	control knobs	music
🎤	microphone	karaoke,music,sing,sound
🎧	headphone	earbud,sound
📻	radio	entertainment,tbt,video
🎷	saxophone	instrument,music
🪗	accordion	box,concertina,instrument,music,squeeze,squeezebox
🎸	guitar	instrument,music,strat
🎹	musical keyboard	instrument,piano
🎺	trumpet	instrument,music
🎻	violin	instrument,music
🪕	banjo	music,stringed
🥁	drum	drumsticks,music
🪘	long drum	beat,conga,instrument,rhythm
🪇	maracas	cha,dance,instrument,music,party,percussion,rattle,shake,shaker
🪈	flute	band,fife,flautist,instrument,marching,music,orchestra,piccolo,pipe,recorder,woodwind
🪉	harp	cupid,instrument,love,music,orchestra
📱	mobile phone	cell,communication,telephone
📲	mobile phone with arrow	build,call,cell,communication,receive,telephone
☎️	telephone	
📞	telephone receiver	communication,voip
📟	pager	communication
📠	fax machine	communication
🔋	battery	
🪫	low battery	drained,electronic,energy,power
🔌	electric plug	electricity
💻	laptop	computer,office,pc,personal
🖥️	desktop computer	monitor
🖨️	printer	computer
⌨️	keyboard	computer
🖱️	computer mouse	
🖲️	trackball	computer
💽	computer disk	minidisk,optical
💾	floppy disk	computer
💿	optical disk	blu-ray,cd,computer,dvd
📀	dvd	blu-ray,cd,computer,disk,optical
🧮	abacus	calculation,calculator
🎥	movie camera	bollywood,cinema,film,hollywood,record
🎞️	film frames	cinema,movie
📽️	film projector	cinema,movie,video
🎬	clapper board	action,movie
📺	television	tv,video
📷	camera	photo,selfie,snap,tbt,trip,video
📸	camera with flash	video
📹	video camera	camcorder,tbt
📼	videocassette	old,school,tape,vcr,vhs
🔍	magnifying glass tilted left	lab,left-pointing,science,search,tool
🔎	magnifying glass tilted right	contact,lab,right-pointing,science,search,tool
🕯️	candle	light
💡	light bulb	comic,electric,idea
🔦	flashlight	electric,tool,torch
🏮	red paper lantern	bar,light,restaurant
🪔	diya lamp	light,oil
📔	notebook with decorative cover	decorated,education,school,writing
📕	closed book	education
📖	open book	education,fantasy,knowledge,library,novels,reading
📗	green book	education,fantasy,library,reading
📘	blue book	education,fantasy,library,reading
📙	orange book	education,fantasy,library,reading
📚	books	education,fantasy,knowledge,library,novels,reading,school,study
📓	notebook	
📒	ledger	notebook
📃	page with curl	document,paper
📜	scroll	paper
📄	page facing up	document,paper
📰	newspaper	communication
🗞️	rolled-up newspaper	
📑	bookmark tabs	marker
🔖	bookmark	
🏷️	label	tag
💰	money bag	bank,bet,billion,cash,cost,dollar,gold,million,moneybag,paid,paying,pot,rich,win
🪙	coin	dollar,euro,gold,metal,money,rich,silver,treasure
💴	yen banknote	bill,currency,money
💵	dollar banknote	bill,currency,money
💶	euro banknote	100,bill,currency,money,rich
💷	pound banknote	bill,billion,cash,currency,money,pounds
💸	money with wings	bank,banknote,bill,billion,cash,dollar,fly,million,note,pay
💳	credit card	bank,cash,charge,money,pay
🧾	receipt	accounting,bookkeeping,evidence,invoice,proof
💹	chart increasing with yen	bank,currency,graph,growth,market,money,rise,trend,upward
✉️	envelope	e-mail,email,letter
📧	e-mail	email,letter
📨	incoming envelope	delivering,e-mail,email,letter,mail,receive,sent
📩	envelope with arrow	communication,down,e-mail,email,letter,mail,outgoing,send,sent
📤	outbox tray	email,letter,mail,sent
📥	inbox tray	email,letter,mail,receive,zero
📦	package	box,communication,delivery,parcel,shipping
📫	closed mailbox with raised flag	communication,postbox
📪	closed mailbox with lowered flag	postbox
📬	open mailbox with raised flag	postbox
📭	open mailbox with lowered flag	postbox
📮	postbox	mail,mailbox
🗳️	ballot box with ballot	
✏️	pencil	
✒️	black nib	pen
🖋️	fountain pen	
🖊️	pen	ballpoint
🖌️	paintbrush	painting
🖍️	crayon	
📝	memo	communication,media,notes,pencil
💼	briefcase	office
📁	file folder	
📂	open file folder	
🗂️	card index dividers	
📅	calendar	date
📆	tear-off calendar	
🗒️	spiral notepad	
🗓️	spiral calendar	pad
📇	card index	old,rolodex,school
📈	chart increasing	data,graph,growth,right,trend,up,upward
📉	chart decreasing	data,down,downward,graph,negative,trend
📊	bar chart	data,graph
📋	clipboard	do,list,notes
📌	pushpin	collage
📍	round pushpin	location,map
📎	paperclip	
🖇️	linked paperclips	
📏	straight ruler	angle,edge,math,straightedge
📐	triangular ruler	angle,math,set,slide,triangle
✂️	scissors	cut,cutting,paper,tool
🗃️	card file box	
🗄️	file cabinet	filing,paper
🗑️	wastebasket	can,garbage,trash
🔒	locked	closed,private
🔓	unlocked	cracked,open
🔏	locked with pen	ink,nib,privacy
🔐	locked with key	bike,closed,secure
🔑	key	keys,lock,major,password,unlock
🗝️	old key	clue,lock
🔨	hammer	home,improvement,repairs,tool
🪓	axe	chop,hatchet,split,wood
⛏️	pick	hammer,mining,tool
⚒️	hammer and pick	tool
🛠️	hammer and wrench	spanner,tool
🗡️	dagger	knife,weapon
⚔️	crossed swords	weapon
💣	bomb	boom,comic,dangerous,explosion,hot
🪃	boomerang	rebound,repercussion,weapon
🏹	bow and arrow	archer,archery,sagittarius,tool,weapon,zodiac
🛡️	shield	weapon
🪚	carpentry saw	carpenter,cut,lumber,tool,trim
🔧	wrench	home,improvement,spanner,tool
🪛	screwdriver	flathead,handy,tool
🔩	nut and bolt	home,improvement,tool
⚙️	gear	cog,cogwheel,tool
🗜️	clamp	compress,tool,vice
⚖️	balance scale	justice,libra,scales,tool,weight,zodiac
🦯	white cane	accessibility,blind,probing
🔗	link	links
⛓️‍💥	broken chain	break,breaking,cuffs,freedom
⛓️	chains	
🪝	hook	catch,crook,curve,ensnare,point,selling
🧰	toolbox	chest,mechanic,red
🧲	magnet	attraction,horseshoe,magnetic,negative,positive,shape,u
🪜	ladder	climb,rung,step
🪏	shovel	bury,dig,garden,hole,plant,scoop,snow,spade
⚗️	alembic	chemistry,tool
🧪	test tube	chemist,chemistry,experiment,lab,science
🧫	petri dish	bacteria,biologist,biology,culture,lab
🧬	dna	biologist,evolution,gene,genetics,life
🔬	microscope	experiment,lab,science,tool
🔭	telescope	contact,extraterrestrial,science,tool
📡	satellite antenna	aliens,contact,dish,science
💉	syringe	doctor,flu,medicine,needle,shot,sick,tool,vaccination
🩸	drop of blood	bleed,donation,injury,medicine,menstruation
💊	pill	doctor,drugs,medicated,medicine,pills,sick,vitamin
🩹	adhesive bandage	
🩼	crutch	aid,cane,disability,help,hurt,injured,mobility,stick
🩺	stethoscope	doctor,heart,medicine
🩻	x-ray	bones,doctor,medical,skeleton,skull,xray
🚪	door	back,closet,front
🛗	elevator	accessibility,hoist,lift
🪞	mirror	makeup,reflection,reflector,speculum
🪟	window	air,frame,fresh,opening,transparent,view
🛏️	bed	hotel,sleep
🛋️	couch and lamp	hotel
🪑	chair	seat,sit
🚽	toilet	bathroom
🪠	plunger	cup,force,plumber,poop,suction,toilet
🚿	shower	water
🛁	bathtub	
🪤	mouse trap	bait,cheese,lure,mousetrap,snare
🪒	razor	sharp,shave
🧴	lotion bottle	moisturizer,shampoo,sunscreen
🧷	safety pin	diaper,punk,rock
🧹	broom	cleaning,sweeping,witch
🧺	basket	farming,laundry,picnic
🧻	roll of paper	toilet,towels
🪣	bucket	cask,pail,vat
🧼	soap	bar,bathing,clean,cleaning,lather,soapdish
🫧	bubbles	burp,clean,floating,pearl,soap,underwater
🪥	toothbrush	bathroom,clean,dental,hygiene,teeth,toiletry
🧽	sponge	absorbing,cleaning,porous,soak
🧯	fire extinguisher	quench
🛒	shopping cart	trolley
🚬	cigarette	smoking
⚰️	coffin	dead,death,vampire
🪦	headstone	cemetery,dead,grave,graveyard,memorial,rip,tomb,tombstone
⚱️	funeral urn	ashes,death
🧿	nazar amulet	bead,blue,charm,evil-eye,talisman
🪬	hamsa	amulet,fatima,fortune,guide,hand,mary,miriam,palm,protect,protection
🗿	moai	face,moyai,statue,stoneface,travel
🪧	placard	demonstration,notice,picket,plaque,protest,sign
🪪	identification card	credentials,document,license,security
""",
        EmojiGroup.SYMBOLS to """
🏧	ATM sign	atm,automated,bank,cash,money,teller
🚮	litter in bin sign	litterbin
🚰	potable water	drinking
♿	wheelchair symbol	access,handicap
🚹	men’s room	bathroom,lavatory,man,restroom,toilet,wc
🚺	women’s room	bathroom,lavatory,restroom,toilet,wc,woman
🚻	restroom	bathroom,lavatory,toilet,wc
🚼	baby symbol	changing
🚾	water closet	bathroom,lavatory,restroom,toilet,wc
🛂	passport control	
🛃	customs	packing
🛄	baggage claim	arrived,bags,case,checked,journey,packing,plane,ready,travel,trip
🛅	left luggage	baggage,case,locker
⚠️	warning	caution
🚸	children crossing	pedestrian,traffic
⛔	no entry	do,fail,forbidden,not,pass,prohibited,traffic
🚫	prohibited	entry,forbidden,no,not,smoke
🚳	no bicycles	bike,forbidden,not,prohibited
🚭	no smoking	forbidden,not,prohibited,smoke
🚯	no littering	forbidden,not,prohibited
🚱	non-potable water	dry,non-drinking,prohibited
🚷	no pedestrians	forbidden,not,prohibited
📵	no mobile phones	cell,forbidden,not,prohibited,telephone
🔞	no one under eighteen	18,age,forbidden,not,prohibited,restriction,underage
☢️	radioactive	sign
☣️	biohazard	sign
⬆️	up arrow	cardinal,direction,north
↗️	up-right arrow	direction,intercardinal,northeast
➡️	right arrow	cardinal,direction,east
↘️	down-right arrow	direction,intercardinal,southeast
⬇️	down arrow	cardinal,direction,south
↙️	down-left arrow	direction,intercardinal,southwest
⬅️	left arrow	cardinal,direction,west
↖️	up-left arrow	direction,intercardinal,northwest
↕️	up-down arrow	
↔️	left-right arrow	
↩️	right arrow curving left	
↪️	left arrow curving right	
⤴️	right arrow curving up	
⤵️	right arrow curving down	
🔃	clockwise vertical arrows	refresh,reload
🔄	counterclockwise arrows button	again,anticlockwise,deja,refresh,rewindershins,vu
🔙	BACK arrow	back
🔚	END arrow	end
🔛	ON! arrow	mark
🔜	SOON arrow	brb,omw,soon
🔝	TOP arrow	homie,top,up
🛐	place of worship	pray,religion
⚛️	atom symbol	atheist
🕉️	om	hindu,religion
✡️	star of David	david,jew,jewish,judaism,religion
☸️	wheel of dharma	buddhist,religion
☯️	yin yang	difficult,lives,religion,tao,taoist,total,yinyang
✝️	latin cross	christ,christian,religion
☦️	orthodox cross	christian,religion
☪️	star and crescent	islam,muslim,ramadan,religion
☮️	peace symbol	healing,peaceful
🕎	menorah	candelabrum,candlestick,hanukkah,jewish,judaism,religion
🔯	dotted six-pointed star	fortune,jewish,judaism
🪯	khanda	deg,fateh,khalsa,religion,sikh,sikhism,tegh
♈	Aries	aries,horoscope,ram,zodiac
♉	Taurus	bull,horoscope,ox,taurus,zodiac
♊	Gemini	gemini,horoscope,twins,zodiac
♋	Cancer	cancer,crab,horoscope,zodiac
♌	Leo	horoscope,leo,lion,zodiac
♍	Virgo	horoscope,virgo,zodiac
♎	Libra	balance,horoscope,justice,libra,scales,zodiac
♏	Scorpio	horoscope,scorpio,scorpion,scorpius,zodiac
♐	Sagittarius	archer,horoscope,sagittarius,zodiac
♑	Capricorn	capricorn,goat,horoscope,zodiac
♒	Aquarius	aquarius,bearer,horoscope,water,zodiac
♓	Pisces	fish,horoscope,pisces,zodiac
⛎	Ophiuchus	bearer,ophiuchus,serpent,snake,zodiac
🔀	shuffle tracks button	arrow,crossed
🔁	repeat button	arrow,clockwise
🔂	repeat single button	arrow,clockwise,once
▶️	play button	arrow,right,triangle
⏩	fast-forward button	arrow,double
⏭️	next track button	arrow,scene,triangle
⏯️	play or pause button	arrow,right,triangle
◀️	reverse button	arrow,left,triangle
⏪	fast reverse button	arrow,double,rewind
⏮️	last track button	arrow,previous,scene,triangle
🔼	upwards button	arrow,red
⏫	fast up button	arrow,double
🔽	downwards button	arrow,red
⏬	fast down button	arrow,double
⏸️	pause button	bar,double,vertical
⏹️	stop button	square
⏺️	record button	circle
⏏️	eject button	
🎦	cinema	camera,film,movie
🔅	dim button	brightness,low
🔆	bright button	brightness,light
📶	antenna bars	cell,communication,mobile,phone,signal,telephone
🛜	wireless	broadband,computer,connectivity,hotspot,internet,network,router,smartphone,wi-fi,wifi,wlan
📳	vibration mode	cell,communication,mobile,phone,telephone
📴	mobile phone off	cell,telephone
♀️	female sign	woman
♂️	male sign	man
⚧️	transgender symbol	
✖️	multiply	cancel,multiplication,sign,x
➕	plus	+
➖	minus	-,heavy,math,sign
➗	divide	division,heavy,math,sign
🟰	heavy equals sign	answer,equality,math
♾️	infinity	forever,unbounded,universal
‼️	double exclamation mark	bangbang,punctuation
⁉️	exclamation question mark	interrobang,punctuation
❓	red question mark	punctuation
❔	white question mark	outlined,punctuation
❕	white exclamation mark	bang,outlined,punctuation
❗	red exclamation mark	bang,punctuation
〰️	wavy dash	punctuation
💱	currency exchange	bank,money
💲	heavy dollar sign	billion,cash,charge,currency,million,money,pay
⚕️	medical symbol	aesculapius,medicine,staff
♻️	recycling symbol	recycle
⚜️	fleur-de-lis	knights
🔱	trident emblem	anchor,poseidon,ship,tool
📛	name badge	
🔰	Japanese symbol for beginner	chevron,green,japanese,leaf,tool,yellow
⭕	hollow red circle	heavy,large
✅	check mark button	checked,checkmark,complete,completed,done,fixed,tick
☑️	check box with check	ballot,checked,done,off,tick
✔️	check mark	checked,checkmark,done,heavy,tick
❌	cross mark	cancel,multiplication,multiply,x
❎	cross mark button	multiplication,multiply,square,x
➰	curly loop	
➿	double curly loop	
〽️	part alternation mark	
✳️	eight-spoked asterisk	
✴️	eight-pointed star	
❇️	sparkle	
©️	copyright	
®️	registered	
™️	trade mark	tm,trademark
🫟	splatter	drip,holi,ink,liquid,mess,paint,spill,stain
#️⃣	keycap: #	
*️⃣	keycap: *	
0️⃣	keycap: 0	zero
1️⃣	keycap: 1	one
2️⃣	keycap: 2	two
3️⃣	keycap: 3	three
4️⃣	keycap: 4	four
5️⃣	keycap: 5	five
6️⃣	keycap: 6	six
7️⃣	keycap: 7	seven
8️⃣	keycap: 8	eight
9️⃣	keycap: 9	nine
🔟	keycap: 10	
🔠	input latin uppercase	abcd,letters
🔡	input latin lowercase	abcd,letters
🔢	input numbers	1234
🔣	input symbols	
🔤	input latin letters	abc,alphabet
🅰️	A button (blood type)	
🆎	AB button (blood type)	ab
🅱️	B button (blood type)	
🆑	CL button	cl
🆒	COOL button	cool
🆓	FREE button	free
ℹ️	information	
🆔	ID button	id,identity
Ⓜ️	circled M	m
🆕	NEW button	new
🆖	NG button	ng
🅾️	O button (blood type)	
🆗	OK button	ok,okay
🅿️	P button	p,parking
🆘	SOS button	help,sos
🆙	UP! button	mark,up
🆚	VS button	versus,vs
🈁	Japanese “here” button	japanese,katakana
🈂️	Japanese “service charge” button	japanese,katakana
🈷️	Japanese “monthly amount” button	ideograph,japanese
🈶	Japanese “not free of charge” button	ideograph,japanese
🈯	Japanese “reserved” button	ideograph,japanese
🉐	Japanese “bargain” button	ideograph,japanese
🈹	Japanese “discount” button	ideograph,japanese
🈚	Japanese “free of charge” button	ideograph,japanese
🈲	Japanese “prohibited” button	ideograph,japanese
🉑	Japanese “acceptable” button	ideograph,japanese
🈸	Japanese “application” button	ideograph,japanese
🈴	Japanese “passing grade” button	ideograph,japanese
🈳	Japanese “vacancy” button	ideograph,japanese
㊗️	Japanese “congratulations” button	ideograph,japanese
㊙️	Japanese “secret” button	ideograph,japanese
🈺	Japanese “open for business” button	ideograph,japanese
🈵	Japanese “no vacancy” button	ideograph,japanese
🔴	red circle	geometric
🟠	orange circle	
🟡	yellow circle	
🟢	green circle	
🔵	blue circle	geometric
🟣	purple circle	
🟤	brown circle	
⚫	black circle	geometric
⚪	white circle	geometric
🟥	red square	card,penalty
🟧	orange square	
🟨	yellow square	card,penalty
🟩	green square	
🟦	blue square	
🟪	purple square	
🟫	brown square	
⬛	black large square	geometric
⬜	white large square	geometric
◼️	black medium square	geometric
◻️	white medium square	geometric
◾	black medium-small square	geometric
◽	white medium-small square	geometric
▪️	black small square	geometric
▫️	white small square	geometric
🔶	large orange diamond	geometric
🔷	large blue diamond	geometric
🔸	small orange diamond	geometric
🔹	small blue diamond	geometric
🔺	red triangle pointed up	geometric
🔻	red triangle pointed down	geometric
💠	diamond with a dot	comic,geometric
🔘	radio button	geometric
🔳	white square button	geometric,outlined
🔲	black square button	geometric
""",
        EmojiGroup.FLAGS to """
🏁	chequered flag	checkered,finish,flags,game,race,racing,sport,win
🚩	triangular flag	construction,golf,post
🎌	crossed flags	celebration,japanese
🏴	black flag	waving
🏳️	white flag	waving
🏳️‍🌈	rainbow flag	bisexual,gay,genderqueer,glbt,glbtq,lesbian,lgbt,lgbtq,lgbtqia,pride,queer,trans,transgender
🏳️‍⚧️	transgender flag	blue,light,pink,white
🏴‍☠️	pirate flag	jolly,plunder,roger,treasure
🇦🇨	flag: Ascension Island	
🇦🇩	flag: Andorra	
🇦🇪	flag: United Arab Emirates	
🇦🇫	flag: Afghanistan	
🇦🇬	flag: Antigua & Barbuda	
🇦🇮	flag: Anguilla	
🇦🇱	flag: Albania	
🇦🇲	flag: Armenia	
🇦🇴	flag: Angola	
🇦🇶	flag: Antarctica	
🇦🇷	flag: Argentina	
🇦🇸	flag: American Samoa	
🇦🇹	flag: Austria	
🇦🇺	flag: Australia	
🇦🇼	flag: Aruba	
🇦🇽	flag: Åland Islands	
🇦🇿	flag: Azerbaijan	
🇧🇦	flag: Bosnia & Herzegovina	
🇧🇧	flag: Barbados	
🇧🇩	flag: Bangladesh	
🇧🇪	flag: Belgium	
🇧🇫	flag: Burkina Faso	
🇧🇬	flag: Bulgaria	
🇧🇭	flag: Bahrain	
🇧🇮	flag: Burundi	
🇧🇯	flag: Benin	
🇧🇱	flag: St. Barthélemy	
🇧🇲	flag: Bermuda	
🇧🇳	flag: Brunei	
🇧🇴	flag: Bolivia	
🇧🇶	flag: Caribbean Netherlands	
🇧🇷	flag: Brazil	
🇧🇸	flag: Bahamas	
🇧🇹	flag: Bhutan	
🇧🇻	flag: Bouvet Island	
🇧🇼	flag: Botswana	
🇧🇾	flag: Belarus	
🇧🇿	flag: Belize	
🇨🇦	flag: Canada	
🇨🇨	flag: Cocos (Keeling) Islands	
🇨🇩	flag: Congo - Kinshasa	
🇨🇫	flag: Central African Republic	
🇨🇬	flag: Congo - Brazzaville	
🇨🇭	flag: Switzerland	
🇨🇮	flag: Côte d’Ivoire	
🇨🇰	flag: Cook Islands	
🇨🇱	flag: Chile	
🇨🇲	flag: Cameroon	
🇨🇳	flag: China	
🇨🇴	flag: Colombia	
🇨🇵	flag: Clipperton Island	
🇨🇶	flag: Sark	
🇨🇷	flag: Costa Rica	
🇨🇺	flag: Cuba	
🇨🇻	flag: Cape Verde	
🇨🇼	flag: Curaçao	
🇨🇽	flag: Christmas Island	
🇨🇾	flag: Cyprus	
🇨🇿	flag: Czechia	
🇩🇪	flag: Germany	
🇩🇬	flag: Diego Garcia	
🇩🇯	flag: Djibouti	
🇩🇰	flag: Denmark	
🇩🇲	flag: Dominica	
🇩🇴	flag: Dominican Republic	
🇩🇿	flag: Algeria	
🇪🇦	flag: Ceuta & Melilla	
🇪🇨	flag: Ecuador	
🇪🇪	flag: Estonia	
🇪🇬	flag: Egypt	
🇪🇭	flag: Western Sahara	
🇪🇷	flag: Eritrea	
🇪🇸	flag: Spain	
🇪🇹	flag: Ethiopia	
🇪🇺	flag: European Union	
🇫🇮	flag: Finland	
🇫🇯	flag: Fiji	
🇫🇰	flag: Falkland Islands	
🇫🇲	flag: Micronesia	
🇫🇴	flag: Faroe Islands	
🇫🇷	flag: France	
🇬🇦	flag: Gabon	
🇬🇧	flag: United Kingdom	
🇬🇩	flag: Grenada	
🇬🇪	flag: Georgia	
🇬🇫	flag: French Guiana	
🇬🇬	flag: Guernsey	
🇬🇭	flag: Ghana	
🇬🇮	flag: Gibraltar	
🇬🇱	flag: Greenland	
🇬🇲	flag: Gambia	
🇬🇳	flag: Guinea	
🇬🇵	flag: Guadeloupe	
🇬🇶	flag: Equatorial Guinea	
🇬🇷	flag: Greece	
🇬🇸	flag: South Georgia & South Sandwich Islands	
🇬🇹	flag: Guatemala	
🇬🇺	flag: Guam	
🇬🇼	flag: Guinea-Bissau	
🇬🇾	flag: Guyana	
🇭🇰	flag: Hong Kong SAR China	
🇭🇲	flag: Heard & McDonald Islands	
🇭🇳	flag: Honduras	
🇭🇷	flag: Croatia	
🇭🇹	flag: Haiti	
🇭🇺	flag: Hungary	
🇮🇨	flag: Canary Islands	
🇮🇩	flag: Indonesia	
🇮🇪	flag: Ireland	
🇮🇱	flag: Israel	
🇮🇲	flag: Isle of Man	
🇮🇳	flag: India	
🇮🇴	flag: British Indian Ocean Territory	
🇮🇶	flag: Iraq	
🇮🇷	flag: Iran	
🇮🇸	flag: Iceland	
🇮🇹	flag: Italy	
🇯🇪	flag: Jersey	
🇯🇲	flag: Jamaica	
🇯🇴	flag: Jordan	
🇯🇵	flag: Japan	
🇰🇪	flag: Kenya	
🇰🇬	flag: Kyrgyzstan	
🇰🇭	flag: Cambodia	
🇰🇮	flag: Kiribati	
🇰🇲	flag: Comoros	
🇰🇳	flag: St. Kitts & Nevis	
🇰🇵	flag: North Korea	
🇰🇷	flag: South Korea	
🇰🇼	flag: Kuwait	
🇰🇾	flag: Cayman Islands	
🇰🇿	flag: Kazakhstan	
🇱🇦	flag: Laos	
🇱🇧	flag: Lebanon	
🇱🇨	flag: St. Lucia	
🇱🇮	flag: Liechtenstein	
🇱🇰	flag: Sri Lanka	
🇱🇷	flag: Liberia	
🇱🇸	flag: Lesotho	
🇱🇹	flag: Lithuania	
🇱🇺	flag: Luxembourg	
🇱🇻	flag: Latvia	
🇱🇾	flag: Libya	
🇲🇦	flag: Morocco	
🇲🇨	flag: Monaco	
🇲🇩	flag: Moldova	
🇲🇪	flag: Montenegro	
🇲🇫	flag: St. Martin	
🇲🇬	flag: Madagascar	
🇲🇭	flag: Marshall Islands	
🇲🇰	flag: North Macedonia	
🇲🇱	flag: Mali	
🇲🇲	flag: Myanmar (Burma)	
🇲🇳	flag: Mongolia	
🇲🇴	flag: Macao SAR China	
🇲🇵	flag: Northern Mariana Islands	
🇲🇶	flag: Martinique	
🇲🇷	flag: Mauritania	
🇲🇸	flag: Montserrat	
🇲🇹	flag: Malta	
🇲🇺	flag: Mauritius	
🇲🇻	flag: Maldives	
🇲🇼	flag: Malawi	
🇲🇽	flag: Mexico	
🇲🇾	flag: Malaysia	
🇲🇿	flag: Mozambique	
🇳🇦	flag: Namibia	
🇳🇨	flag: New Caledonia	
🇳🇪	flag: Niger	
🇳🇫	flag: Norfolk Island	
🇳🇬	flag: Nigeria	
🇳🇮	flag: Nicaragua	
🇳🇱	flag: Netherlands	
🇳🇴	flag: Norway	
🇳🇵	flag: Nepal	
🇳🇷	flag: Nauru	
🇳🇺	flag: Niue	
🇳🇿	flag: New Zealand	
🇴🇲	flag: Oman	
🇵🇦	flag: Panama	
🇵🇪	flag: Peru	
🇵🇫	flag: French Polynesia	
🇵🇬	flag: Papua New Guinea	
🇵🇭	flag: Philippines	
🇵🇰	flag: Pakistan	
🇵🇱	flag: Poland	
🇵🇲	flag: St. Pierre & Miquelon	
🇵🇳	flag: Pitcairn Islands	
🇵🇷	flag: Puerto Rico	
🇵🇸	flag: Palestinian Territories	
🇵🇹	flag: Portugal	
🇵🇼	flag: Palau	
🇵🇾	flag: Paraguay	
🇶🇦	flag: Qatar	
🇷🇪	flag: Réunion	
🇷🇴	flag: Romania	
🇷🇸	flag: Serbia	
🇷🇺	flag: Russia	
🇷🇼	flag: Rwanda	
🇸🇦	flag: Saudi Arabia	
🇸🇧	flag: Solomon Islands	
🇸🇨	flag: Seychelles	
🇸🇩	flag: Sudan	
🇸🇪	flag: Sweden	
🇸🇬	flag: Singapore	
🇸🇭	flag: St. Helena	
🇸🇮	flag: Slovenia	
🇸🇯	flag: Svalbard & Jan Mayen	
🇸🇰	flag: Slovakia	
🇸🇱	flag: Sierra Leone	
🇸🇲	flag: San Marino	
🇸🇳	flag: Senegal	
🇸🇴	flag: Somalia	
🇸🇷	flag: Suriname	
🇸🇸	flag: South Sudan	
🇸🇹	flag: São Tomé & Príncipe	
🇸🇻	flag: El Salvador	
🇸🇽	flag: Sint Maarten	
🇸🇾	flag: Syria	
🇸🇿	flag: Eswatini	
🇹🇦	flag: Tristan da Cunha	
🇹🇨	flag: Turks & Caicos Islands	
🇹🇩	flag: Chad	
🇹🇫	flag: French Southern Territories	
🇹🇬	flag: Togo	
🇹🇭	flag: Thailand	
🇹🇯	flag: Tajikistan	
🇹🇰	flag: Tokelau	
🇹🇱	flag: Timor-Leste	
🇹🇲	flag: Turkmenistan	
🇹🇳	flag: Tunisia	
🇹🇴	flag: Tonga	
🇹🇷	flag: Türkiye	
🇹🇹	flag: Trinidad & Tobago	
🇹🇻	flag: Tuvalu	
🇹🇼	flag: Taiwan	
🇹🇿	flag: Tanzania	
🇺🇦	flag: Ukraine	
🇺🇬	flag: Uganda	
🇺🇲	flag: U.S. Outlying Islands	
🇺🇳	flag: United Nations	
🇺🇸	flag: United States	
🇺🇾	flag: Uruguay	
🇺🇿	flag: Uzbekistan	
🇻🇦	flag: Vatican City	
🇻🇨	flag: St. Vincent & Grenadines	
🇻🇪	flag: Venezuela	
🇻🇬	flag: British Virgin Islands	
🇻🇮	flag: U.S. Virgin Islands	
🇻🇳	flag: Vietnam	
🇻🇺	flag: Vanuatu	
🇼🇫	flag: Wallis & Futuna	
🇼🇸	flag: Samoa	
🇽🇰	flag: Kosovo	
🇾🇪	flag: Yemen	
🇾🇹	flag: Mayotte	
🇿🇦	flag: South Africa	
🇿🇲	flag: Zambia	
🇿🇼	flag: Zimbabwe	
🏴󠁧󠁢󠁥󠁮󠁧󠁿	flag: England	
🏴󠁧󠁢󠁳󠁣󠁴󠁿	flag: Scotland	
🏴󠁧󠁢󠁷󠁬󠁳󠁿	flag: Wales	
""",
    )
}
