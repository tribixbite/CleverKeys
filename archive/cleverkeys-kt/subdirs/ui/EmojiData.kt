package tribixbite.cleverkeys.ui

/**
 * Emoji categories and data for the emoji picker.
 *
 * Unicode 15.1 emoji support with Material 3 categorization.
 *
 * @since v2.1.0
 */
object EmojiData {

    /**
     * Emoji category enumeration matching Material Design guidelines
     */
    enum class Category(val displayName: String, val icon: String) {
        RECENT("Recent", "🕐"),
        SMILEYS("Smileys & Emotion", "😀"),
        PEOPLE("People & Body", "👋"),
        ANIMALS("Animals & Nature", "🐶"),
        FOOD("Food & Drink", "🍎"),
        TRAVEL("Travel & Places", "🚗"),
        ACTIVITIES("Activities", "⚽"),
        OBJECTS("Objects", "💡"),
        SYMBOLS("Symbols", "❤️"),
        FLAGS("Flags", "🏁")
    }

    /**
     * Emoji item with metadata
     */
    data class Emoji(
        val char: String,
        val description: String,
        val keywords: List<String> = emptyList(),
        val skinToneSupport: Boolean = false
    )

    /**
     * Smileys & Emotion category (100 emojis)
     */
    val smileys = listOf(
        Emoji("😀", "grinning face", listOf("smile", "happy", "joy")),
        Emoji("😃", "grinning face with big eyes", listOf("smile", "happy")),
        Emoji("😄", "grinning face with smiling eyes", listOf("smile", "happy", "joy")),
        Emoji("😁", "beaming face with smiling eyes", listOf("smile", "grin")),
        Emoji("😆", "grinning squinting face", listOf("laugh", "happy")),
        Emoji("😅", "grinning face with sweat", listOf("smile", "sweat", "relief")),
        Emoji("🤣", "rolling on the floor laughing", listOf("laugh", "rofl")),
        Emoji("😂", "face with tears of joy", listOf("laugh", "cry", "joy")),
        Emoji("🙂", "slightly smiling face", listOf("smile")),
        Emoji("🙃", "upside-down face", listOf("silly", "sarcasm")),
        Emoji("😉", "winking face", listOf("wink", "flirt")),
        Emoji("😊", "smiling face with smiling eyes", listOf("smile", "happy", "blush")),
        Emoji("😇", "smiling face with halo", listOf("angel", "innocent")),
        Emoji("🥰", "smiling face with hearts", listOf("love", "adore")),
        Emoji("😍", "smiling face with heart-eyes", listOf("love", "crush")),
        Emoji("🤩", "star-struck", listOf("eyes", "star", "impressed")),
        Emoji("😘", "face blowing a kiss", listOf("kiss", "love")),
        Emoji("😗", "kissing face", listOf("kiss")),
        Emoji("☺️", "smiling face", listOf("smile", "happy")),
        Emoji("😚", "kissing face with closed eyes", listOf("kiss")),
        Emoji("😙", "kissing face with smiling eyes", listOf("kiss")),
        Emoji("🥲", "smiling face with tear", listOf("sad", "cry", "grateful")),
        Emoji("😋", "face savoring food", listOf("yummy", "delicious")),
        Emoji("😛", "face with tongue", listOf("tongue", "playful")),
        Emoji("😜", "winking face with tongue", listOf("wink", "playful")),
        Emoji("🤪", "zany face", listOf("goofy", "crazy")),
        Emoji("😝", "squinting face with tongue", listOf("playful")),
        Emoji("🤑", "money-mouth face", listOf("money", "rich")),
        Emoji("🤗", "smiling face with open hands", listOf("hug")),
        Emoji("🤭", "face with hand over mouth", listOf("quiet", "oops")),
        Emoji("🫢", "face with open eyes and hand over mouth", listOf("gasp", "surprise")),
        Emoji("🫣", "face with peeking eye", listOf("peek", "shy")),
        Emoji("🤫", "shushing face", listOf("quiet", "shh")),
        Emoji("🤔", "thinking face", listOf("think", "hmm")),
        Emoji("🫡", "saluting face", listOf("salute", "respect")),
        Emoji("🤐", "zipper-mouth face", listOf("silence", "secret")),
        Emoji("🤨", "face with raised eyebrow", listOf("skeptical", "suspicious")),
        Emoji("😐", "neutral face", listOf("neutral", "meh")),
        Emoji("😑", "expressionless face", listOf("blank", "deadpan")),
        Emoji("😶", "face without mouth", listOf("silence", "speechless")),
        Emoji("🫥", "dotted line face", listOf("invisible", "depressed")),
        Emoji("😶‍🌫️", "face in clouds", listOf("foggy")),
        Emoji("😏", "smirking face", listOf("smirk", "smug")),
        Emoji("😒", "unamused face", listOf("unimpressed", "meh")),
        Emoji("🙄", "face with rolling eyes", listOf("eyeroll")),
        Emoji("😬", "grimacing face", listOf("awkward", "cringe")),
        Emoji("😮‍💨", "face exhaling", listOf("sigh", "relief")),
        Emoji("🤥", "lying face", listOf("pinocchio", "lie")),
        Emoji("😌", "relieved face", listOf("calm", "peaceful")),
        Emoji("😔", "pensive face", listOf("sad", "depressed")),
        Emoji("😪", "sleepy face", listOf("tired", "sleep")),
        Emoji("🤤", "drooling face", listOf("drool")),
        Emoji("😴", "sleeping face", listOf("sleep", "zzz")),
        Emoji("😷", "face with medical mask", listOf("sick", "mask")),
        Emoji("🤒", "face with thermometer", listOf("sick", "fever")),
        Emoji("🤕", "face with head-bandage", listOf("hurt", "injured")),
        Emoji("🤢", "nauseated face", listOf("sick", "gross")),
        Emoji("🤮", "face vomiting", listOf("sick", "puke")),
        Emoji("🤧", "sneezing face", listOf("sick", "sneeze")),
        Emoji("🥵", "hot face", listOf("hot", "sweat")),
        Emoji("🥶", "cold face", listOf("cold", "freezing")),
        Emoji("🥴", "woozy face", listOf("dizzy", "drunk")),
        Emoji("😵", "face with crossed-out eyes", listOf("dizzy", "dead")),
        Emoji("😵‍💫", "face with spiral eyes", listOf("dizzy", "confused")),
        Emoji("🤯", "exploding head", listOf("mind blown")),
        Emoji("🤠", "cowboy hat face", listOf("cowboy")),
        Emoji("🥳", "partying face", listOf("party", "celebrate")),
        Emoji("🥸", "disguised face", listOf("disguise", "incognito")),
        Emoji("😎", "smiling face with sunglasses", listOf("cool", "sunglasses")),
        Emoji("🤓", "nerd face", listOf("nerd", "geek")),
        Emoji("🧐", "face with monocle", listOf("fancy", "scrutiny")),
        Emoji("😕", "confused face", listOf("confused")),
        Emoji("🫤", "face with diagonal mouth", listOf("meh", "unsure")),
        Emoji("😟", "worried face", listOf("worried", "concerned")),
        Emoji("🙁", "slightly frowning face", listOf("sad", "frown")),
        Emoji("☹️", "frowning face", listOf("sad", "unhappy")),
        Emoji("😮", "face with open mouth", listOf("surprise", "wow")),
        Emoji("😯", "hushed face", listOf("surprise", "shocked")),
        Emoji("😲", "astonished face", listOf("shock", "gasp")),
        Emoji("😳", "flushed face", listOf("embarrassed", "blush")),
        Emoji("🥺", "pleading face", listOf("puppy eyes", "beg")),
        Emoji("🥹", "face holding back tears", listOf("touched", "grateful")),
        Emoji("😦", "frowning face with open mouth", listOf("frown", "sad")),
        Emoji("😧", "anguished face", listOf("anguish", "pain")),
        Emoji("😨", "fearful face", listOf("scared", "fear")),
        Emoji("😰", "anxious face with sweat", listOf("nervous", "anxious")),
        Emoji("😥", "sad but relieved face", listOf("sad", "relieved")),
        Emoji("😢", "crying face", listOf("cry", "sad", "tear")),
        Emoji("😭", "loudly crying face", listOf("cry", "sob")),
        Emoji("😱", "face screaming in fear", listOf("scream", "horror")),
        Emoji("😖", "confounded face", listOf("frustrated", "confused")),
        Emoji("😣", "persevering face", listOf("struggle", "pain")),
        Emoji("😞", "disappointed face", listOf("disappointed", "sad")),
        Emoji("😓", "downcast face with sweat", listOf("sweat", "stress")),
        Emoji("😩", "weary face", listOf("tired", "exhausted")),
        Emoji("😫", "tired face", listOf("tired", "frustrated")),
        Emoji("🥱", "yawning face", listOf("tired", "bored", "yawn")),
        Emoji("😤", "face with steam from nose", listOf("angry", "triumph")),
        Emoji("😡", "enraged face", listOf("angry", "mad", "rage")),
        Emoji("😠", "angry face", listOf("angry", "mad")),
        Emoji("🤬", "face with symbols on mouth", listOf("swear", "cursing"))
    )

    /**
     * People & Body category (50 emojis)
     */
    val people = listOf(
        Emoji("👋", "waving hand", listOf("hello", "hi", "bye"), skinToneSupport = true),
        Emoji("🤚", "raised back of hand", listOf("hand"), skinToneSupport = true),
        Emoji("🖐️", "hand with fingers splayed", listOf("hand"), skinToneSupport = true),
        Emoji("✋", "raised hand", listOf("hand", "stop"), skinToneSupport = true),
        Emoji("🖖", "vulcan salute", listOf("spock", "star trek"), skinToneSupport = true),
        Emoji("👌", "OK hand", listOf("ok", "okay"), skinToneSupport = true),
        Emoji("🤌", "pinched fingers", listOf("italian"), skinToneSupport = true),
        Emoji("🤏", "pinching hand", listOf("small"), skinToneSupport = true),
        Emoji("✌️", "victory hand", listOf("peace", "victory"), skinToneSupport = true),
        Emoji("🤞", "crossed fingers", listOf("luck", "hope"), skinToneSupport = true),
        Emoji("🫰", "hand with index finger and thumb crossed", listOf("love", "money"), skinToneSupport = true),
        Emoji("🤟", "love-you gesture", listOf("love", "ily"), skinToneSupport = true),
        Emoji("🤘", "sign of the horns", listOf("rock", "metal"), skinToneSupport = true),
        Emoji("🤙", "call me hand", listOf("call", "phone"), skinToneSupport = true),
        Emoji("👈", "backhand index pointing left", listOf("point", "left"), skinToneSupport = true),
        Emoji("👉", "backhand index pointing right", listOf("point", "right"), skinToneSupport = true),
        Emoji("👆", "backhand index pointing up", listOf("point", "up"), skinToneSupport = true),
        Emoji("🖕", "middle finger", listOf("rude"), skinToneSupport = true),
        Emoji("👇", "backhand index pointing down", listOf("point", "down"), skinToneSupport = true),
        Emoji("☝️", "index pointing up", listOf("point", "up"), skinToneSupport = true),
        Emoji("🫵", "index pointing at the viewer", listOf("point", "you"), skinToneSupport = true),
        Emoji("👍", "thumbs up", listOf("like", "yes", "approve"), skinToneSupport = true),
        Emoji("👎", "thumbs down", listOf("dislike", "no"), skinToneSupport = true),
        Emoji("✊", "raised fist", listOf("fist", "power"), skinToneSupport = true),
        Emoji("👊", "oncoming fist", listOf("fist", "punch"), skinToneSupport = true),
        Emoji("🤛", "left-facing fist", listOf("fist"), skinToneSupport = true),
        Emoji("🤜", "right-facing fist", listOf("fist"), skinToneSupport = true),
        Emoji("👏", "clapping hands", listOf("clap", "applause"), skinToneSupport = true),
        Emoji("🙌", "raising hands", listOf("celebrate", "hooray"), skinToneSupport = true),
        Emoji("🫶", "heart hands", listOf("love", "heart"), skinToneSupport = true),
        Emoji("👐", "open hands", listOf("hands"), skinToneSupport = true),
        Emoji("🤲", "palms up together", listOf("prayer", "please"), skinToneSupport = true),
        Emoji("🤝", "handshake", listOf("deal", "agreement")),
        Emoji("🙏", "folded hands", listOf("pray", "thanks", "please"), skinToneSupport = true),
        Emoji("✍️", "writing hand", listOf("write"), skinToneSupport = true),
        Emoji("💅", "nail polish", listOf("nails", "polish"), skinToneSupport = true),
        Emoji("🤳", "selfie", listOf("selfie", "camera"), skinToneSupport = true),
        Emoji("💪", "flexed biceps", listOf("strong", "muscle"), skinToneSupport = true),
        Emoji("🦾", "mechanical arm", listOf("robot", "prosthetic")),
        Emoji("🦿", "mechanical leg", listOf("robot", "prosthetic")),
        Emoji("🦵", "leg", listOf("leg", "kick"), skinToneSupport = true),
        Emoji("🦶", "foot", listOf("foot", "kick"), skinToneSupport = true),
        Emoji("👂", "ear", listOf("hear", "listen"), skinToneSupport = true),
        Emoji("🦻", "ear with hearing aid", listOf("deaf", "hearing aid"), skinToneSupport = true),
        Emoji("👃", "nose", listOf("smell"), skinToneSupport = true),
        Emoji("🧠", "brain", listOf("smart", "think")),
        Emoji("🫀", "anatomical heart", listOf("heart", "organ")),
        Emoji("🫁", "lungs", listOf("lungs", "breathe")),
        Emoji("🦷", "tooth", listOf("dentist")),
        Emoji("🦴", "bone", listOf("skeleton"))
    )

    /**
     * Animals & Nature category (40 emojis)
     */
    val animals = listOf(
        Emoji("🐶", "dog face", listOf("dog", "puppy", "pet")),
        Emoji("🐱", "cat face", listOf("cat", "kitty", "pet")),
        Emoji("🐭", "mouse face", listOf("mouse")),
        Emoji("🐹", "hamster", listOf("hamster", "pet")),
        Emoji("🐰", "rabbit face", listOf("rabbit", "bunny")),
        Emoji("🦊", "fox", listOf("fox")),
        Emoji("🐻", "bear", listOf("bear")),
        Emoji("🐼", "panda", listOf("panda", "bear")),
        Emoji("🐻‍❄️", "polar bear", listOf("polar", "bear")),
        Emoji("🐨", "koala", listOf("koala")),
        Emoji("🐯", "tiger face", listOf("tiger")),
        Emoji("🦁", "lion", listOf("lion")),
        Emoji("🐮", "cow face", listOf("cow")),
        Emoji("🐷", "pig face", listOf("pig")),
        Emoji("🐸", "frog", listOf("frog")),
        Emoji("🐵", "monkey face", listOf("monkey")),
        Emoji("🙈", "see-no-evil monkey", listOf("monkey", "hide")),
        Emoji("🙉", "hear-no-evil monkey", listOf("monkey", "deaf")),
        Emoji("🙊", "speak-no-evil monkey", listOf("monkey", "quiet")),
        Emoji("🐒", "monkey", listOf("monkey")),
        Emoji("🦍", "gorilla", listOf("gorilla")),
        Emoji("🦧", "orangutan", listOf("orangutan")),
        Emoji("🐔", "chicken", listOf("chicken")),
        Emoji("🐧", "penguin", listOf("penguin")),
        Emoji("🐦", "bird", listOf("bird")),
        Emoji("🐤", "baby chick", listOf("chick", "bird")),
        Emoji("🦆", "duck", listOf("duck")),
        Emoji("🦅", "eagle", listOf("eagle", "bird")),
        Emoji("🦉", "owl", listOf("owl", "bird")),
        Emoji("🦇", "bat", listOf("bat")),
        Emoji("🐺", "wolf", listOf("wolf")),
        Emoji("🐗", "boar", listOf("boar", "pig")),
        Emoji("🐴", "horse face", listOf("horse")),
        Emoji("🦄", "unicorn", listOf("unicorn", "magic")),
        Emoji("🐝", "honeybee", listOf("bee", "honey")),
        Emoji("🪱", "worm", listOf("worm")),
        Emoji("🐛", "bug", listOf("bug", "insect")),
        Emoji("🦋", "butterfly", listOf("butterfly")),
        Emoji("🐌", "snail", listOf("snail", "slow")),
        Emoji("🐞", "lady beetle", listOf("ladybug", "insect"))
    )

    /**
     * Food & Drink category (40 emojis)
     */
    val food = listOf(
        Emoji("🍎", "red apple", listOf("apple", "fruit")),
        Emoji("🍊", "tangerine", listOf("orange", "fruit")),
        Emoji("🍋", "lemon", listOf("lemon", "fruit")),
        Emoji("🍌", "banana", listOf("banana", "fruit")),
        Emoji("🍉", "watermelon", listOf("watermelon", "fruit")),
        Emoji("🍇", "grapes", listOf("grapes", "fruit")),
        Emoji("🍓", "strawberry", listOf("strawberry", "fruit")),
        Emoji("🫐", "blueberries", listOf("blueberry", "fruit")),
        Emoji("🍈", "melon", listOf("melon", "fruit")),
        Emoji("🍒", "cherries", listOf("cherry", "fruit")),
        Emoji("🍑", "peach", listOf("peach", "fruit")),
        Emoji("🥭", "mango", listOf("mango", "fruit")),
        Emoji("🍍", "pineapple", listOf("pineapple", "fruit")),
        Emoji("🥥", "coconut", listOf("coconut", "fruit")),
        Emoji("🥝", "kiwi fruit", listOf("kiwi", "fruit")),
        Emoji("🍅", "tomato", listOf("tomato", "vegetable")),
        Emoji("🍆", "eggplant", listOf("eggplant", "vegetable")),
        Emoji("🥑", "avocado", listOf("avocado", "fruit")),
        Emoji("🥦", "broccoli", listOf("broccoli", "vegetable")),
        Emoji("🥬", "leafy green", listOf("lettuce", "vegetable")),
        Emoji("🥒", "cucumber", listOf("cucumber", "vegetable")),
        Emoji("🌶️", "hot pepper", listOf("pepper", "spicy")),
        Emoji("🫑", "bell pepper", listOf("pepper", "vegetable")),
        Emoji("🌽", "ear of corn", listOf("corn", "vegetable")),
        Emoji("🥕", "carrot", listOf("carrot", "vegetable")),
        Emoji("🧄", "garlic", listOf("garlic", "vegetable")),
        Emoji("🧅", "onion", listOf("onion", "vegetable")),
        Emoji("🥔", "potato", listOf("potato", "vegetable")),
        Emoji("🍠", "roasted sweet potato", listOf("sweet potato", "vegetable")),
        Emoji("🥐", "croissant", listOf("croissant", "bread")),
        Emoji("🥖", "baguette bread", listOf("bread", "baguette")),
        Emoji("🥨", "pretzel", listOf("pretzel", "snack")),
        Emoji("🥯", "bagel", listOf("bagel", "bread")),
        Emoji("🥞", "pancakes", listOf("pancakes", "breakfast")),
        Emoji("🧇", "waffle", listOf("waffle", "breakfast")),
        Emoji("🧀", "cheese wedge", listOf("cheese")),
        Emoji("🍖", "meat on bone", listOf("meat", "bone")),
        Emoji("🍗", "poultry leg", listOf("chicken", "meat")),
        Emoji("🥩", "cut of meat", listOf("steak", "meat")),
        Emoji("🥓", "bacon", listOf("bacon", "meat"))
    )

    /**
     * Travel & Places category (30 emojis)
     */
    val travel = listOf(
        Emoji("🚗", "automobile", listOf("car", "vehicle")),
        Emoji("🚕", "taxi", listOf("taxi", "cab")),
        Emoji("🚙", "sport utility vehicle", listOf("suv", "vehicle")),
        Emoji("🚌", "bus", listOf("bus", "vehicle")),
        Emoji("🚎", "trolleybus", listOf("trolley", "bus")),
        Emoji("🏎️", "racing car", listOf("race", "car")),
        Emoji("🚓", "police car", listOf("police", "cop")),
        Emoji("🚑", "ambulance", listOf("ambulance", "emergency")),
        Emoji("🚒", "fire engine", listOf("fire", "truck")),
        Emoji("🚐", "minibus", listOf("minibus", "van")),
        Emoji("🛻", "pickup truck", listOf("truck", "pickup")),
        Emoji("🚚", "delivery truck", listOf("truck", "delivery")),
        Emoji("🚛", "articulated lorry", listOf("truck", "semi")),
        Emoji("🚜", "tractor", listOf("tractor", "farm")),
        Emoji("🏍️", "motorcycle", listOf("motorcycle", "bike")),
        Emoji("🛵", "motor scooter", listOf("scooter", "vespa")),
        Emoji("🚲", "bicycle", listOf("bike", "bicycle")),
        Emoji("🛴", "kick scooter", listOf("scooter")),
        Emoji("✈️", "airplane", listOf("airplane", "flight")),
        Emoji("🚁", "helicopter", listOf("helicopter")),
        Emoji("🚂", "locomotive", listOf("train", "steam")),
        Emoji("🚆", "train", listOf("train", "railway")),
        Emoji("🚇", "metro", listOf("metro", "subway")),
        Emoji("🚈", "light rail", listOf("rail", "train")),
        Emoji("🚉", "station", listOf("station", "train")),
        Emoji("🚊", "tram", listOf("tram", "trolley")),
        Emoji("🚝", "monorail", listOf("monorail")),
        Emoji("🚞", "mountain railway", listOf("railway", "mountain")),
        Emoji("🚋", "tram car", listOf("tram")),
        Emoji("🚃", "railway car", listOf("train", "car"))
    )

    /**
     * Activities category (20 emojis)
     */
    val activities = listOf(
        Emoji("⚽", "soccer ball", listOf("soccer", "football", "ball")),
        Emoji("🏀", "basketball", listOf("basketball", "ball")),
        Emoji("🏈", "american football", listOf("football", "ball")),
        Emoji("⚾", "baseball", listOf("baseball", "ball")),
        Emoji("🥎", "softball", listOf("softball", "ball")),
        Emoji("🎾", "tennis", listOf("tennis", "ball")),
        Emoji("🏐", "volleyball", listOf("volleyball", "ball")),
        Emoji("🏉", "rugby football", listOf("rugby", "ball")),
        Emoji("🥏", "flying disc", listOf("frisbee", "disc")),
        Emoji("🎱", "pool 8 ball", listOf("pool", "billiards")),
        Emoji("🪀", "yo-yo", listOf("yoyo", "toy")),
        Emoji("🏓", "ping pong", listOf("ping pong", "table tennis")),
        Emoji("🏸", "badminton", listOf("badminton", "racket")),
        Emoji("🏒", "ice hockey", listOf("hockey", "ice")),
        Emoji("🏑", "field hockey", listOf("hockey", "field")),
        Emoji("🥍", "lacrosse", listOf("lacrosse")),
        Emoji("🏏", "cricket game", listOf("cricket")),
        Emoji("🪃", "boomerang", listOf("boomerang")),
        Emoji("🥅", "goal net", listOf("goal", "net")),
        Emoji("⛳", "flag in hole", listOf("golf", "flag"))
    )

    /**
     * Objects category (20 emojis)
     */
    val objects = listOf(
        Emoji("💡", "light bulb", listOf("idea", "light")),
        Emoji("🔦", "flashlight", listOf("flashlight", "torch")),
        Emoji("🕯️", "candle", listOf("candle", "light")),
        Emoji("🪔", "diya lamp", listOf("lamp", "diwali")),
        Emoji("💻", "laptop", listOf("computer", "laptop")),
        Emoji("🖥️", "desktop computer", listOf("computer", "desktop")),
        Emoji("⌨️", "keyboard", listOf("keyboard", "type")),
        Emoji("🖱️", "computer mouse", listOf("mouse", "click")),
        Emoji("🖨️", "printer", listOf("printer", "print")),
        Emoji("📱", "mobile phone", listOf("phone", "mobile")),
        Emoji("☎️", "telephone", listOf("phone", "call")),
        Emoji("📞", "telephone receiver", listOf("phone", "call")),
        Emoji("📟", "pager", listOf("pager", "beeper")),
        Emoji("📠", "fax machine", listOf("fax")),
        Emoji("🔋", "battery", listOf("battery", "power")),
        Emoji("🪫", "low battery", listOf("battery", "low")),
        Emoji("🔌", "electric plug", listOf("plug", "power")),
        Emoji("💾", "floppy disk", listOf("disk", "save")),
        Emoji("💿", "optical disk", listOf("cd", "disc")),
        Emoji("📀", "dvd", listOf("dvd", "disc"))
    )

    /**
     * Symbols category (30 emojis)
     */
    val symbols = listOf(
        Emoji("❤️", "red heart", listOf("love", "heart")),
        Emoji("🧡", "orange heart", listOf("love", "heart")),
        Emoji("💛", "yellow heart", listOf("love", "heart")),
        Emoji("💚", "green heart", listOf("love", "heart")),
        Emoji("💙", "blue heart", listOf("love", "heart")),
        Emoji("💜", "purple heart", listOf("love", "heart")),
        Emoji("🖤", "black heart", listOf("love", "heart")),
        Emoji("🤍", "white heart", listOf("love", "heart")),
        Emoji("🤎", "brown heart", listOf("love", "heart")),
        Emoji("💔", "broken heart", listOf("heartbreak", "sad")),
        Emoji("❣️", "heart exclamation", listOf("love", "exclamation")),
        Emoji("💕", "two hearts", listOf("love", "hearts")),
        Emoji("💞", "revolving hearts", listOf("love", "hearts")),
        Emoji("💓", "beating heart", listOf("love", "heartbeat")),
        Emoji("💗", "growing heart", listOf("love", "heart")),
        Emoji("💖", "sparkling heart", listOf("love", "sparkle")),
        Emoji("💘", "heart with arrow", listOf("love", "cupid")),
        Emoji("💝", "heart with ribbon", listOf("love", "gift")),
        Emoji("💟", "heart decoration", listOf("love", "heart")),
        Emoji("☮️", "peace symbol", listOf("peace")),
        Emoji("✝️", "latin cross", listOf("cross", "christian")),
        Emoji("☪️", "star and crescent", listOf("islam", "muslim")),
        Emoji("🕉️", "om", listOf("hindu", "om")),
        Emoji("☸️", "wheel of dharma", listOf("buddhist")),
        Emoji("✡️", "star of david", listOf("jewish", "judaism")),
        Emoji("🔯", "dotted six-pointed star", listOf("star")),
        Emoji("🕎", "menorah", listOf("jewish", "candelabra")),
        Emoji("☯️", "yin yang", listOf("balance", "tao")),
        Emoji("☦️", "orthodox cross", listOf("cross", "orthodox")),
        Emoji("🛐", "place of worship", listOf("worship", "pray"))
    )

    /**
     * Flags category (20 emojis - popular flags)
     */
    val flags = listOf(
        Emoji("🏁", "chequered flag", listOf("finish", "race")),
        Emoji("🚩", "triangular flag", listOf("flag")),
        Emoji("🎌", "crossed flags", listOf("japan", "flags")),
        Emoji("🏴", "black flag", listOf("pirate")),
        Emoji("🏳️", "white flag", listOf("surrender")),
        Emoji("🏳️‍🌈", "rainbow flag", listOf("pride", "lgbt")),
        Emoji("🏳️‍⚧️", "transgender flag", listOf("transgender", "trans")),
        Emoji("🏴‍☠️", "pirate flag", listOf("pirate", "skull")),
        Emoji("🇺🇸", "flag: United States", listOf("usa", "america")),
        Emoji("🇬🇧", "flag: United Kingdom", listOf("uk", "britain")),
        Emoji("🇨🇦", "flag: Canada", listOf("canada")),
        Emoji("🇦🇺", "flag: Australia", listOf("australia")),
        Emoji("🇩🇪", "flag: Germany", listOf("germany")),
        Emoji("🇫🇷", "flag: France", listOf("france")),
        Emoji("🇪🇸", "flag: Spain", listOf("spain")),
        Emoji("🇮🇹", "flag: Italy", listOf("italy")),
        Emoji("🇯🇵", "flag: Japan", listOf("japan")),
        Emoji("🇰🇷", "flag: South Korea", listOf("korea", "south korea")),
        Emoji("🇨🇳", "flag: China", listOf("china")),
        Emoji("🇮🇳", "flag: India", listOf("india"))
    )

    /**
     * Get all emojis for a category
     */
    fun getEmojisForCategory(category: Category): List<Emoji> = when (category) {
        Category.RECENT -> emptyList() // Populated dynamically
        Category.SMILEYS -> smileys
        Category.PEOPLE -> people
        Category.ANIMALS -> animals
        Category.FOOD -> food
        Category.TRAVEL -> travel
        Category.ACTIVITIES -> activities
        Category.OBJECTS -> objects
        Category.SYMBOLS -> symbols
        Category.FLAGS -> flags
    }

    /**
     * Search emojis by keyword
     */
    fun searchEmojis(query: String): List<Emoji> {
        val lowerQuery = query.lowercase().trim()
        if (lowerQuery.isEmpty()) return emptyList()

        val allCategories = listOf(smileys, people, animals, food, travel, activities, objects, symbols, flags)
        return allCategories.flatten().filter { emoji ->
            emoji.description.contains(lowerQuery, ignoreCase = true) ||
            emoji.keywords.any { it.contains(lowerQuery, ignoreCase = true) }
        }
    }
}
