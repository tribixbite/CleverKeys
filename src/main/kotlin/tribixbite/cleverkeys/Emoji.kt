package tribixbite.cleverkeys

import android.content.res.Resources
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

class Emoji protected constructor(bytecode: String) {
    private val kv: KeyValue = KeyValue(bytecode, KeyValue.Kind.String, 0, 0)

    fun kv(): KeyValue = kv

    companion object {
        private val all: MutableList<Emoji> = mutableListOf()
        private val groups: MutableList<List<Emoji>> = mutableListOf()
        private val stringMap: MutableMap<String, Emoji> = mutableMapOf()
        // #41: Name-to-emoji map for search
        private val nameMap: MutableMap<String, Emoji> = mutableMapOf()

        @JvmStatic
        fun init(res: Resources) {
            if (all.isNotEmpty()) return

            try {
                val inputStream = res.openRawResource(R.raw.emojis)
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    // Read emoji (until empty line)
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        val e = Emoji(line)
                        all.add(e)
                        stringMap[line] = e
                    }

                    // Read group indices
                    val groupLine = reader.readLine()
                    if (groupLine != null) {
                        val tokens = groupLine.split(" ")
                        var last = 0
                        for (i in 1 until tokens.size) {
                            val next = tokens[i].toInt()
                            groups.add(all.subList(last, next))
                            last = next
                        }
                        groups.add(all.subList(last, all.size))
                    }
                }
            } catch (e: IOException) {
                Logs.exn("Emoji.init() failed", e)
            }
        }

        @JvmStatic
        fun getNumGroups(): Int = groups.size

        @JvmStatic
        fun getEmojisByGroup(groupIndex: Int): List<Emoji> = groups[groupIndex]

        @JvmStatic
        fun getEmojiByString(value: String): Emoji? = stringMap[value]

        /**
         * #41: Search emojis by name.
         * Searches both the emoji names and keywords.
         * @param query The search query (case-insensitive)
         * @return List of matching emojis
         */
        @JvmStatic
        fun searchByName(query: String): List<Emoji> {
            if (query.isBlank()) return emptyList()

            // Initialize name map on first search
            if (nameMap.isEmpty()) {
                initNameMap()
            }

            val queryLower = query.lowercase().trim()
            val results = mutableListOf<Emoji>()
            val seen = mutableSetOf<String>()

            // Search by name
            for ((name, emoji) in nameMap) {
                if (name.contains(queryLower) && emoji.kv().getString() !in seen) {
                    results.add(emoji)
                    seen.add(emoji.kv().getString())
                }
            }

            return results.take(100) // Limit results
        }

        /**
         * #41: Initialize the name-to-emoji map with comprehensive emoji names.
         * Includes 500+ emoji names from mapOldNameToValue plus common aliases.
         */
        private fun initNameMap() {
            // Comprehensive emoji name mappings (500+ entries)
            val nameToEmoji = mapOf(
                // Faces - Smiling
                "grinning" to "😀", "smiley" to "😃", "smile" to "😄", "grin" to "😁",
                "satisfied" to "😆", "sweat smile" to "😅", "joy" to "😂", "wink" to "😉",
                "blush" to "😊", "innocent" to "😇", "heart eyes" to "😍", "kissing heart" to "😘",
                "kissing" to "😗", "kissing closed eyes" to "😚", "kissing smiling eyes" to "😙",
                "yum" to "😋", "stuck out tongue" to "😛", "stuck out tongue winking eye" to "😜",
                "stuck out tongue closed eyes" to "😝", "money mouth" to "🤑", "hugging" to "🤗",
                "nerd" to "🤓", "sunglasses" to "😎", "star struck" to "🤩", "partying" to "🥳",

                // Faces - Neutral/Skeptical
                "neutral face" to "😐", "expressionless" to "😑", "no mouth" to "😶",
                "smirk" to "😏", "unamused" to "😒", "grimacing" to "😬", "face with rolling eyes" to "🙄",
                "relieved" to "😌", "pensive" to "😔", "sleepy" to "😪", "sleeping" to "😴",
                "drooling" to "🤤", "thinking" to "🤔", "shushing" to "🤫", "lying" to "🤥",
                "zipper mouth" to "🤐", "raised eyebrow" to "🤨", "monocle" to "🧐",

                // Faces - Negative
                "mask" to "😷", "dizzy face" to "😵", "confused" to "😕", "worried" to "😟",
                "open mouth" to "😮", "hushed" to "😯", "astonished" to "😲", "flushed" to "😳",
                "frowning" to "😦", "anguished" to "😧", "fearful" to "😨", "cold sweat" to "😰",
                "disappointed relieved" to "😥", "cry" to "😢", "sob" to "😭", "scream" to "😱",
                "confounded" to "😖", "persevere" to "😣", "disappointed" to "😞", "sweat" to "😓",
                "weary" to "😩", "tired face" to "😫", "triumph" to "😤", "rage" to "😡",
                "angry" to "😠", "face with symbols" to "🤬", "exploding head" to "🤯",
                "nauseated" to "🤢", "vomiting" to "🤮", "sneezing" to "🤧", "hot" to "🥵",
                "cold" to "🥶", "woozy" to "🥴", "dead" to "💀", "skull" to "💀",

                // Fantasy/Creatures
                "smiling imp" to "😈", "imp" to "👿", "poop" to "💩", "shit" to "💩",
                "ghost" to "👻", "alien" to "👽", "robot" to "🤖", "jack o lantern" to "🎃",
                "clown" to "🤡", "ogre" to "👹", "goblin" to "👺", "skull crossbones" to "☠️",

                // Gestures & Body
                "wave" to "👋", "raised back of hand" to "🤚", "raised hand" to "✋",
                "vulcan" to "🖖", "ok hand" to "👌", "pinching hand" to "🤏", "victory" to "✌️",
                "crossed fingers" to "🤞", "love you gesture" to "🤟", "rock on" to "🤘",
                "call me" to "🤙", "thumbs up" to "👍", "thumbsup" to "👍", "+1" to "👍",
                "thumbs down" to "👎", "thumbsdown" to "👎", "-1" to "👎", "fist" to "✊",
                "punch" to "👊", "left fist" to "🤛", "right fist" to "🤜", "clap" to "👏",
                "raised hands" to "🙌", "open hands" to "👐", "palms up" to "🤲", "handshake" to "🤝",
                "pray" to "🙏", "writing hand" to "✍️", "nail polish" to "💅", "selfie" to "🤳",
                "muscle" to "💪", "flex" to "💪", "leg" to "🦵", "foot" to "🦶",
                "ear" to "👂", "nose" to "👃", "brain" to "🧠", "tooth" to "🦷",
                "bone" to "🦴", "eyes" to "👀", "eye" to "👁️", "tongue" to "👅", "lips" to "👄",

                // People
                "baby" to "👶", "child" to "🧒", "boy" to "👦", "girl" to "👧",
                "man" to "👨", "woman" to "👩", "older man" to "👴", "older woman" to "👵",
                "person frowning" to "🙍", "person pouting" to "🙎", "no good" to "🙅",
                "ok person" to "🙆", "tipping hand" to "💁", "raising hand" to "🙋",
                "bowing" to "🙇", "facepalm" to "🤦", "shrug" to "🤷", "police" to "👮",
                "detective" to "🕵️", "guard" to "💂", "construction" to "👷", "prince" to "🤴",
                "princess" to "👸", "turban" to "👳", "man with cap" to "👲", "bride" to "👰",
                "pregnant" to "🤰", "santa" to "🎅", "mrs claus" to "🤶", "superhero" to "🦸",
                "supervillain" to "🦹", "mage" to "🧙", "fairy" to "🧚", "vampire" to "🧛",
                "merperson" to "🧜", "elf" to "🧝", "genie" to "🧞", "zombie" to "🧟",

                // Hearts & Love
                "kiss" to "💋", "love letter" to "💌", "cupid" to "💘", "gift heart" to "💝",
                "sparkling heart" to "💖", "growing heart" to "💗", "beating heart" to "💓",
                "revolving hearts" to "💞", "two hearts" to "💕", "heart decoration" to "💟",
                "heart exclamation" to "❣️", "broken heart" to "💔", "heart" to "❤️",
                "red heart" to "❤️", "orange heart" to "🧡", "yellow heart" to "💛",
                "green heart" to "💚", "blue heart" to "💙", "purple heart" to "💜",
                "black heart" to "🖤", "white heart" to "🤍", "brown heart" to "🤎",

                // Animals - Mammals
                "cat" to "🐱", "cat face" to "🐱", "cat2" to "🐈", "dog" to "🐶",
                "dog face" to "🐶", "dog2" to "🐕", "monkey face" to "🐵", "monkey" to "🐒",
                "see no evil" to "🙈", "hear no evil" to "🙉", "speak no evil" to "🙊",
                "horse" to "🐴", "horse face" to "🐴", "racehorse" to "🐎", "unicorn" to "🦄",
                "cow" to "🐮", "cow face" to "🐮", "cow2" to "🐄", "ox" to "🐂",
                "pig" to "🐷", "pig face" to "🐷", "pig2" to "🐖", "pig nose" to "🐽",
                "boar" to "🐗", "mouse" to "🐭", "mouse face" to "🐭", "mouse2" to "🐁",
                "rat" to "🐀", "hamster" to "🐹", "rabbit" to "🐰", "rabbit face" to "🐰",
                "rabbit2" to "🐇", "chipmunk" to "🐿️", "bear" to "🐻", "panda" to "🐼",
                "koala" to "🐨", "tiger" to "🐯", "tiger face" to "🐯", "tiger2" to "🐅",
                "lion" to "🦁", "leopard" to "🐆", "wolf" to "🐺", "fox" to "🦊",
                "raccoon" to "🦝", "gorilla" to "🦍", "elephant" to "🐘", "rhino" to "🦏",
                "hippopotamus" to "🦛", "hippo" to "🦛", "camel" to "🐫", "dromedary" to "🐪",
                "giraffe" to "🦒", "zebra" to "🦓", "deer" to "🦌", "kangaroo" to "🦘",
                "badger" to "🦡", "llama" to "🦙", "hedgehog" to "🦔", "bat" to "🦇",
                "sloth" to "🦥", "otter" to "🦦", "skunk" to "🦨", "orangutan" to "🦧",

                // Animals - Birds
                "chicken" to "🐔", "rooster" to "🐓", "hatching chick" to "🐣", "chick" to "🐤",
                "baby chick" to "🐥", "bird" to "🐦", "penguin" to "🐧", "dove" to "🕊️",
                "eagle" to "🦅", "duck" to "🦆", "swan" to "🦢", "owl" to "🦉",
                "flamingo" to "🦩", "peacock" to "🦚", "parrot" to "🦜", "turkey" to "🦃",

                // Animals - Marine
                "whale" to "🐳", "whale2" to "🐋", "dolphin" to "🐬", "fish" to "🐟",
                "tropical fish" to "🐠", "blowfish" to "🐡", "shark" to "🦈", "octopus" to "🐙",
                "shell" to "🐚", "crab" to "🦀", "lobster" to "🦞", "shrimp" to "🦐",
                "squid" to "🦑", "oyster" to "🦪",

                // Animals - Bugs
                "snail" to "🐌", "butterfly" to "🦋", "bug" to "🐛", "ant" to "🐜",
                "bee" to "🐝", "honeybee" to "🐝", "beetle" to "🪲", "ladybug" to "🐞",
                "cricket" to "🦗", "cockroach" to "🪳", "spider" to "🕷️", "spider web" to "🕸️",
                "scorpion" to "🦂", "mosquito" to "🦟", "fly" to "🪰", "worm" to "🪱",
                "microbe" to "🦠",

                // Animals - Reptiles
                "crocodile" to "🐊", "turtle" to "🐢", "snake" to "🐍", "lizard" to "🦎",
                "dragon" to "🐉", "dragon face" to "🐲", "t-rex" to "🦖", "dinosaur" to "🦕",
                "sauropod" to "🦕",

                // Plants
                "bouquet" to "💐", "cherry blossom" to "🌸", "blossom" to "🌼", "tulip" to "🌷",
                "rose" to "🌹", "wilted flower" to "🥀", "hibiscus" to "🌺", "sunflower" to "🌻",
                "seedling" to "🌱", "evergreen tree" to "🌲", "tree" to "🌲", "deciduous tree" to "🌳",
                "palm tree" to "🌴", "cactus" to "🌵", "ear of rice" to "🌾", "herb" to "🌿",
                "shamrock" to "☘️", "four leaf clover" to "🍀", "clover" to "🍀", "maple leaf" to "🍁",
                "fallen leaf" to "🍂", "leaves" to "🍃", "mushroom" to "🍄",

                // Food - Fruits
                "grapes" to "🍇", "melon" to "🍈", "watermelon" to "🍉", "tangerine" to "🍊",
                "orange" to "🍊", "lemon" to "🍋", "banana" to "🍌", "pineapple" to "🍍",
                "mango" to "🥭", "apple" to "🍎", "green apple" to "🍏", "pear" to "🍐",
                "peach" to "🍑", "cherries" to "🍒", "strawberry" to "🍓", "blueberries" to "🫐",
                "kiwi" to "🥝", "tomato" to "🍅", "olive" to "🫒", "coconut" to "🥥",
                "avocado" to "🥑", "eggplant" to "🍆", "potato" to "🥔", "carrot" to "🥕",
                "corn" to "🌽", "hot pepper" to "🌶️", "pepper" to "🫑", "cucumber" to "🥒",
                "leafy green" to "🥬", "broccoli" to "🥦", "garlic" to "🧄", "onion" to "🧅",
                "peanuts" to "🥜", "chestnut" to "🌰",

                // Food - Prepared
                "bread" to "🍞", "croissant" to "🥐", "baguette" to "🥖", "flatbread" to "🫓",
                "pretzel" to "🥨", "bagel" to "🥯", "pancakes" to "🥞", "waffle" to "🧇",
                "cheese" to "🧀", "meat on bone" to "🍖", "poultry leg" to "🍗", "bacon" to "🥓",
                "hamburger" to "🍔", "burger" to "🍔", "fries" to "🍟", "pizza" to "🍕",
                "hot dog" to "🌭", "hotdog" to "🌭", "sandwich" to "🥪", "taco" to "🌮",
                "burrito" to "🌯", "tamale" to "🫔", "stuffed flatbread" to "🥙", "falafel" to "🧆",
                "egg" to "🥚", "cooking" to "🍳", "fried egg" to "🍳", "shallow pan" to "🥘",
                "stew" to "🍲", "fondue" to "🫕", "bowl with spoon" to "🥣", "salad" to "🥗",
                "popcorn" to "🍿", "butter" to "🧈", "salt" to "🧂", "canned food" to "🥫",

                // Food - Asian
                "bento" to "🍱", "rice cracker" to "🍘", "rice ball" to "🍙", "rice" to "🍚",
                "curry" to "🍛", "ramen" to "🍜", "spaghetti" to "🍝", "sweet potato" to "🍠",
                "oden" to "🍢", "sushi" to "🍣", "fried shrimp" to "🍤", "fish cake" to "🍥",
                "moon cake" to "🥮", "dango" to "🍡", "dumpling" to "🥟", "fortune cookie" to "🥠",
                "takeout box" to "🥡",

                // Food - Sweets
                "ice cream" to "🍨", "shaved ice" to "🍧", "icecream" to "🍦", "doughnut" to "🍩",
                "donut" to "🍩", "cookie" to "🍪", "birthday" to "🎂", "birthday cake" to "🎂",
                "cake" to "🎂", "shortcake" to "🍰", "cupcake" to "🧁", "pie" to "🥧",
                "chocolate" to "🍫", "candy" to "🍬", "lollipop" to "🍭", "custard" to "🍮",
                "honey pot" to "🍯",

                // Drinks
                "baby bottle" to "🍼", "milk" to "🥛", "coffee" to "☕", "tea" to "🍵",
                "teacup" to "🍵", "sake" to "🍶", "champagne" to "🍾", "wine" to "🍷",
                "wine glass" to "🍷", "cocktail" to "🍸", "tropical drink" to "🍹", "beer" to "🍺",
                "beers" to "🍻", "clinking glasses" to "🥂", "tumbler" to "🥃", "whiskey" to "🥃",
                "cup with straw" to "🥤", "bubble tea" to "🧋", "beverage box" to "🧃",
                "mate" to "🧉", "ice" to "🧊",

                // Objects - Tableware
                "chopsticks" to "🥢", "knife fork plate" to "🍽️", "fork knife" to "🍴",
                "fork and knife" to "🍴", "spoon" to "🥄", "kitchen knife" to "🔪", "amphora" to "🏺",

                // Travel & Places
                "earth globe" to "🌍", "globe" to "🌎", "world" to "🌏", "map" to "🗺️",
                "compass" to "🧭", "mountain" to "⛰️", "snow capped mountain" to "🏔️",
                "volcano" to "🌋", "mount fuji" to "🗻", "camping" to "🏕️", "beach" to "🏖️",
                "desert" to "🏜️", "island" to "🏝️", "stadium" to "🏟️", "building" to "🏛️",
                "house" to "🏠", "home" to "🏠", "house building" to "🏡", "office" to "🏢",
                "post office" to "🏣", "hospital" to "🏥", "bank" to "🏦", "hotel" to "🏨",
                "love hotel" to "🏩", "convenience store" to "🏪", "school" to "🏫",
                "department store" to "🏬", "factory" to "🏭", "castle" to "🏰",
                "european castle" to "🏰", "japanese castle" to "🏯", "wedding" to "💒",
                "tokyo tower" to "🗼", "statue of liberty" to "🗽", "church" to "⛪",
                "mosque" to "🕌", "hindu temple" to "🛕", "synagogue" to "🕍", "kaaba" to "🕋",
                "fountain" to "⛲", "tent" to "⛺", "foggy" to "🌁", "night" to "🌃",
                "city sunset" to "🌇", "city sunrise" to "🌆", "bridge" to "🌉", "ferris wheel" to "🎡",
                "roller coaster" to "🎢", "carousel" to "🎠", "barber" to "💈", "circus" to "🎪",

                // Transport - Land
                "locomotive" to "🚂", "train" to "🚆", "railway car" to "🚃", "high speed train" to "🚄",
                "bullet train" to "🚅", "train2" to "🚆", "metro" to "🚇", "light rail" to "🚈",
                "station" to "🚉", "tram" to "🚊", "monorail" to "🚝", "mountain railway" to "🚞",
                "tram car" to "🚋", "bus" to "🚌", "oncoming bus" to "🚍", "trolleybus" to "🚎",
                "minibus" to "🚐", "ambulance" to "🚑", "fire engine" to "🚒", "police car" to "🚓",
                "oncoming police car" to "🚔", "taxi" to "🚕", "oncoming taxi" to "🚖",
                "car" to "🚗", "automobile" to "🚗", "oncoming automobile" to "🚘",
                "suv" to "🚙", "pickup" to "🛻", "truck" to "🚚", "lorry" to "🚛",
                "tractor" to "🚜", "racing car" to "🏎️", "motorcycle" to "🏍️", "scooter" to "🛵",
                "manual wheelchair" to "🦽", "motorized wheelchair" to "🦼", "auto rickshaw" to "🛺",
                "bike" to "🚲", "bicycle" to "🚲", "kick scooter" to "🛴", "skateboard" to "🛹",
                "roller skate" to "🛼", "bus stop" to "🚏", "fuel" to "⛽", "gas" to "⛽",

                // Transport - Water & Air
                "anchor" to "⚓", "boat" to "⛵", "sailboat" to "⛵", "canoe" to "🛶",
                "speedboat" to "🚤", "passenger ship" to "🛳️", "ferry" to "⛴️", "ship" to "🚢",
                "airplane" to "✈️", "plane" to "✈️", "small airplane" to "🛩️", "departures" to "🛫",
                "arrivals" to "🛬", "parachute" to "🪂", "helicopter" to "🚁", "suspension railway" to "🚟",
                "mountain cableway" to "🚠", "aerial tramway" to "🚡", "satellite" to "🛰️",
                "rocket" to "🚀", "flying saucer" to "🛸", "ufo" to "🛸",

                // Time & Weather
                "clock" to "🕐", "alarm clock" to "⏰", "stopwatch" to "⏱️", "timer" to "⏲️",
                "hourglass" to "⌛", "watch" to "⌚", "sun" to "☀️", "sunny" to "🌞",
                "moon" to "🌙", "star" to "⭐", "stars" to "🌟", "cloud" to "☁️",
                "partly sunny" to "⛅", "thunder" to "⛈️", "rain" to "🌧️", "umbrella" to "☂️",
                "umbrella with rain" to "☔", "snowflake" to "❄️", "snow" to "❄️",
                "snowman" to "⛄", "wind" to "💨", "cyclone" to "🌀", "rainbow" to "🌈",
                "lightning" to "⚡", "zap" to "⚡", "fire" to "🔥", "flame" to "🔥",
                "droplet" to "💧", "water" to "💧", "ocean" to "🌊", "wave" to "🌊",

                // Activities & Sports
                "soccer" to "⚽", "baseball" to "⚾", "basketball" to "🏀", "volleyball" to "🏐",
                "football" to "🏈", "rugby" to "🏉", "tennis" to "🎾", "flying disc" to "🥏",
                "bowling" to "🎳", "cricket" to "🏏", "field hockey" to "🏑", "ice hockey" to "🏒",
                "lacrosse" to "🥍", "ping pong" to "🏓", "badminton" to "🏸", "boxing glove" to "🥊",
                "martial arts" to "🥋", "goal" to "🥅", "golf" to "⛳", "ice skate" to "⛸️",
                "fishing" to "🎣", "diving" to "🤿", "running shirt" to "🎽", "ski" to "🎿",
                "sled" to "🛷", "curling" to "🥌", "dart" to "🎯", "target" to "🎯",
                "yo yo" to "🪀", "kite" to "🪁", "pool" to "🎱", "8ball" to "🎱",
                "video game" to "🎮", "controller" to "🎮", "joystick" to "🕹️",
                "slot machine" to "🎰", "game die" to "🎲", "dice" to "🎲", "chess" to "♟️",
                "puzzle" to "🧩", "teddy bear" to "🧸", "pinata" to "🪅", "nesting dolls" to "🪆",

                // Arts & Music
                "art" to "🎨", "palette" to "🎨", "performing arts" to "🎭", "theater" to "🎭",
                "ticket" to "🎫", "tickets" to "🎟️", "film frames" to "🎞️", "movie camera" to "🎥",
                "clapper board" to "🎬", "microphone" to "🎤", "headphones" to "🎧",
                "radio" to "📻", "saxophone" to "🎷", "accordion" to "🪗", "guitar" to "🎸",
                "musical keyboard" to "🎹", "piano" to "🎹", "trumpet" to "🎺", "violin" to "🎻",
                "banjo" to "🪕", "drum" to "🥁", "long drum" to "🪘",

                // Celebration
                "party" to "🎉", "tada" to "🎉", "confetti ball" to "🎊", "confetti" to "🎊",
                "balloon" to "🎈", "ribbon" to "🎀", "gift" to "🎁", "present" to "🎁",
                "christmas" to "🎄", "christmas tree" to "🎄", "sparkler" to "🎇",
                "fireworks" to "🎆", "firecracker" to "🧨", "sparkles" to "✨",

                // Awards
                "trophy" to "🏆", "medal" to "🏅", "sports medal" to "🏅", "gold medal" to "🥇",
                "first place" to "🥇", "silver medal" to "🥈", "second place" to "🥈",
                "bronze medal" to "🥉", "third place" to "🥉", "military medal" to "🎖️",
                "crown" to "👑", "reminder ribbon" to "🎗️",

                // Objects - Clothing
                "running shoe" to "👟", "shoe" to "👞", "high heel" to "👠", "sandal" to "👡",
                "ballet shoes" to "🩰", "boot" to "👢", "womans hat" to "👒", "top hat" to "🎩",
                "graduation cap" to "🎓", "billed cap" to "🧢", "helmet" to "⛑️", "lipstick" to "💄",
                "ring" to "💍", "gem" to "💎", "glasses" to "👓", "sunglasses" to "🕶️",
                "goggles" to "🥽", "lab coat" to "🥼", "safety vest" to "🦺", "necktie" to "👔",
                "shirt" to "👕", "jeans" to "👖", "scarf" to "🧣", "gloves" to "🧤",
                "coat" to "🧥", "socks" to "🧦", "dress" to "👗", "kimono" to "👘",
                "sari" to "🥻", "one piece swimsuit" to "🩱", "briefs" to "🩲", "shorts" to "🩳",
                "bikini" to "👙", "womans clothes" to "👚", "purse" to "👛", "handbag" to "👜",
                "pouch" to "👝", "briefcase" to "💼", "backpack" to "🎒", "thong sandal" to "🩴",
                "luggage" to "🧳", "umbrella" to "☂️",

                // Objects - Tech
                "phone" to "📱", "mobile phone" to "📱", "calling" to "📲", "telephone" to "📞",
                "pager" to "📟", "fax" to "📠", "battery" to "🔋", "plug" to "🔌",
                "computer" to "💻", "laptop" to "💻", "desktop" to "🖥️", "printer" to "🖨️",
                "keyboard" to "⌨️", "mouse" to "🖱️", "trackball" to "🖲️", "disk" to "💽",
                "floppy" to "💾", "cd" to "💿", "dvd" to "📀", "abacus" to "🧮",
                "camera" to "📷", "camera flash" to "📸", "video camera" to "📹", "movie" to "🎥",
                "projector" to "📽️", "tv" to "📺", "television" to "📺", "vhs" to "📼",
                "magnifying glass" to "🔍", "mag" to "🔍", "search" to "🔍", "candle" to "🕯️",
                "bulb" to "💡", "lightbulb" to "💡", "flashlight" to "🔦", "lantern" to "🏮",
                "diya lamp" to "🪔",

                // Objects - Mail & Office
                "notebook" to "📓", "closed book" to "📕", "open book" to "📖", "book" to "📚",
                "books" to "📚", "ledger" to "📒", "page with curl" to "📃", "scroll" to "📜",
                "page facing up" to "📄", "newspaper" to "📰", "bookmark" to "🔖",
                "label" to "🏷️", "money bag" to "💰", "moneybag" to "💰", "yen" to "💴",
                "dollar" to "💵", "cash" to "💵", "euro" to "💶", "pound" to "💷",
                "money with wings" to "💸", "credit card" to "💳", "receipt" to "🧾",
                "envelope" to "✉️", "email" to "📧", "e-mail" to "📧", "incoming envelope" to "📨",
                "envelope with arrow" to "📩", "outbox" to "📤", "inbox" to "📥",
                "package" to "📦", "mailbox" to "📫", "mailbox closed" to "📪",
                "mailbox with mail" to "📬", "mailbox with no mail" to "📭", "postbox" to "📮",
                "ballot box" to "🗳️", "pencil" to "✏️", "pen" to "🖊️", "fountain pen" to "🖋️",
                "paintbrush" to "🖌️", "crayon" to "🖍️", "memo" to "📝", "file folder" to "📁",
                "open file folder" to "📂", "dividers" to "🗂️", "calendar" to "📅",
                "date" to "📅", "spiral calendar" to "🗓️", "card index" to "📇",
                "chart increasing" to "📈", "chart decreasing" to "📉", "bar chart" to "📊",
                "clipboard" to "📋", "pushpin" to "📌", "pin" to "📌", "round pushpin" to "📍",
                "paperclip" to "📎", "linked paperclips" to "🖇️", "straight ruler" to "📏",
                "triangular ruler" to "📐", "scissors" to "✂️", "wastebasket" to "🗑️",

                // Objects - Lock & Tools
                "lock" to "🔒", "locked" to "🔒", "unlock" to "🔓", "unlocked" to "🔓",
                "lock with pen" to "🔏", "lock with key" to "🔐", "key" to "🔑", "old key" to "🗝️",
                "hammer" to "🔨", "axe" to "🪓", "pick" to "⛏️", "hammer and pick" to "⚒️",
                "hammer and wrench" to "🛠️", "dagger" to "🗡️", "sword" to "⚔️",
                "gun" to "🔫", "boomerang" to "🪃", "bow and arrow" to "🏹", "shield" to "🛡️",
                "carpentry saw" to "🪚", "wrench" to "🔧", "screwdriver" to "🪛",
                "nut and bolt" to "🔩", "gear" to "⚙️", "clamp" to "🗜️", "balance scale" to "⚖️",
                "probing cane" to "🦯", "link" to "🔗", "chains" to "⛓️", "hook" to "🪝",
                "toolbox" to "🧰", "magnet" to "🧲", "ladder" to "🪜",

                // Objects - Science & Medical
                "microscope" to "🔬", "telescope" to "🔭", "satellite antenna" to "📡",
                "syringe" to "💉", "drop of blood" to "🩸", "pill" to "💊", "adhesive bandage" to "🩹",
                "stethoscope" to "🩺", "x-ray" to "🩻", "dna" to "🧬", "petri dish" to "🧫",
                "test tube" to "🧪", "thermometer" to "🌡️", "broom" to "🧹", "basket" to "🧺",
                "roll of paper" to "🧻", "toilet" to "🚽", "potable water" to "🚰",
                "shower" to "🚿", "bathtub" to "🛁", "bath" to "🛀", "razor" to "🪒",
                "lotion bottle" to "🧴", "safety pin" to "🧷", "sponge" to "🧽", "bucket" to "🪣",
                "toothbrush" to "🪥", "soap" to "🧼", "mouse trap" to "🪤", "mirror" to "🪞",
                "window" to "🪟",

                // Objects - Household
                "bed" to "🛏️", "couch" to "🛋️", "chair" to "🪑", "door" to "🚪",
                "elevator" to "🛗", "picture frame" to "🖼️",

                // Symbols - Common
                "100" to "💯", "hundred" to "💯", "check" to "✅", "check mark" to "✅",
                "x" to "❌", "cross" to "❌", "cross mark" to "❌", "question" to "❓",
                "exclamation" to "❗", "warning" to "⚠️", "caution" to "⚠️",
                "no entry" to "⛔", "prohibited" to "🚫", "plus" to "➕", "minus" to "➖",
                "multiply" to "✖️", "divide" to "➗", "infinity" to "♾️", "recycle" to "♻️",
                "copyright" to "©️", "registered" to "®️", "trademark" to "™️",
                "zzz" to "💤", "sleep" to "💤", "boom" to "💥", "collision" to "💥",
                "sweat drops" to "💦", "dash" to "💨", "hole" to "🕳️", "bomb" to "💣",
                "speech balloon" to "💬", "thought balloon" to "💭", "anger" to "💢",
                "right anger" to "🗯️",

                // Symbols - Arrows
                "up arrow" to "⬆️", "down arrow" to "⬇️", "left arrow" to "⬅️", "right arrow" to "➡️",
                "arrow up" to "⬆️", "arrow down" to "⬇️", "arrow left" to "⬅️", "arrow right" to "➡️",
                "arrows clockwise" to "🔃", "arrows counterclockwise" to "🔄",
                "back" to "🔙", "end" to "🔚", "on" to "🔛", "soon" to "🔜", "top" to "🔝",

                // Symbols - Religious
                "star of david" to "✡️", "om" to "🕉️", "wheel of dharma" to "☸️",
                "yin yang" to "☯️", "latin cross" to "✝️", "orthodox cross" to "☦️",
                "star and crescent" to "☪️", "peace" to "☮️", "menorah" to "🕎",
                "six pointed star" to "🔯",

                // Symbols - Zodiac
                "aries" to "♈", "taurus" to "♉", "gemini" to "♊", "cancer" to "♋",
                "leo" to "♌", "virgo" to "♍", "libra" to "♎", "scorpius" to "♏",
                "sagittarius" to "♐", "capricorn" to "♑", "aquarius" to "♒", "pisces" to "♓",
                "ophiuchus" to "⛎",

                // Flags
                "checkered flag" to "🏁", "flag" to "🚩", "triangular flag" to "🚩",
                "crossed flags" to "🎌", "black flag" to "🏴", "white flag" to "🏳️",
                "rainbow flag" to "🏳️‍🌈", "pirate flag" to "🏴‍☠️"
            )

            for ((name, emojiStr) in nameToEmoji) {
                val emoji = stringMap[emojiStr]
                if (emoji != null) {
                    // Store with multiple variations for better search
                    nameMap[name] = emoji
                    nameMap[name.replace(" ", "_")] = emoji
                    nameMap[name.replace(" ", "")] = emoji
                }
            }
        }

        @JvmStatic
        @Throws(IllegalArgumentException::class)
        fun mapOldNameToValue(name: String): String {
            if (name.matches(Regex(":(u[a-fA-F0-9]{4,5})+:"))) {
                return buildString {
                    for (code in name.replace(":", "").substring(1).split("u")) {
                        try {
                            append(Character.toChars(Integer.decode("0X$code")))
                        } catch (e: IllegalArgumentException) {
                            throw IllegalArgumentException("Failed to parse codepoint '$code' in name '$name'", e)
                        }
                    }
                }
            }

            return when (name) {
                ":grinning:" -> "😀"
                ":smiley:" -> "😃"
                ":smile:" -> "😄"
                ":grin:" -> "😁"
                ":satisfied:" -> "😆"
                ":sweat_smile:" -> "😅"
                ":joy:" -> "😂"
                ":wink:" -> "😉"
                ":blush:" -> "😊"
                ":innocent:" -> "😇"
                ":heart_eyes:" -> "😍"
                ":kissing_heart:" -> "😘"
                ":kissing:" -> "😗"
                ":kissing_closed_eyes:" -> "😚"
                ":kissing_smiling_eyes:" -> "😙"
                ":yum:" -> "😋"
                ":stuck_out_tongue:" -> "😛"
                ":stuck_out_tongue_winking_eye:" -> "😜"
                ":stuck_out_tongue_closed_eyes:" -> "😝"
                ":neutral_face:" -> "😐"
                ":expressionless:" -> "😑"
                ":no_mouth:" -> "😶"
                ":smirk:" -> "😏"
                ":unamused:" -> "😒"
                ":grimacing:" -> "😬"
                ":relieved:" -> "😌"
                ":pensive:" -> "😔"
                ":sleepy:" -> "😪"
                ":sleeping:" -> "😴"
                ":mask:" -> "😷"
                ":dizzy_face:" -> "😵"
                ":sunglasses:" -> "😎"
                ":confused:" -> "😕"
                ":worried:" -> "😟"
                ":open_mouth:" -> "😮"
                ":hushed:" -> "😯"
                ":astonished:" -> "😲"
                ":flushed:" -> "😳"
                ":frowning:" -> "😦"
                ":anguished:" -> "😧"
                ":fearful:" -> "😨"
                ":cold_sweat:" -> "😰"
                ":disappointed_relieved:" -> "😥"
                ":cry:" -> "😢"
                ":sob:" -> "😭"
                ":scream:" -> "😱"
                ":confounded:" -> "😖"
                ":persevere:" -> "😣"
                ":disappointed:" -> "😞"
                ":sweat:" -> "😓"
                ":weary:" -> "😩"
                ":tired_face:" -> "😫"
                ":triumph:" -> "😤"
                ":rage:" -> "😡"
                ":angry:" -> "😠"
                ":smiling_imp:" -> "😈"
                ":imp:" -> "👿"
                ":skull:" -> "💀"
                ":shit:" -> "💩"
                ":japanese_ogre:" -> "👹"
                ":japanese_goblin:" -> "👺"
                ":ghost:" -> "👻"
                ":alien:" -> "👽"
                ":space_invader:" -> "👾"
                ":smiley_cat:" -> "😺"
                ":smile_cat:" -> "😸"
                ":joy_cat:" -> "😹"
                ":heart_eyes_cat:" -> "😻"
                ":smirk_cat:" -> "😼"
                ":kissing_cat:" -> "😽"
                ":scream_cat:" -> "🙀"
                ":crying_cat_face:" -> "😿"
                ":pouting_cat:" -> "😾"
                ":see_no_evil:" -> "🙈"
                ":hear_no_evil:" -> "🙉"
                ":speak_no_evil:" -> "🙊"
                ":kiss:" -> "💋"
                ":love_letter:" -> "💌"
                ":cupid:" -> "💘"
                ":gift_heart:" -> "💝"
                ":sparkling_heart:" -> "💖"
                ":heartpulse:" -> "💗"
                ":heartbeat:" -> "💓"
                ":revolving_hearts:" -> "💞"
                ":two_hearts:" -> "💕"
                ":heart_decoration:" -> "💟"
                ":broken_heart:" -> "💔"
                ":yellow_heart:" -> "💛"
                ":green_heart:" -> "💚"
                ":blue_heart:" -> "💙"
                ":purple_heart:" -> "💜"
                ":100:" -> "💯"
                ":anger:" -> "💢"
                ":collision:" -> "💥"
                ":dizzy:" -> "💫"
                ":sweat_drops:" -> "💦"
                ":dash:" -> "💨"
                ":bomb:" -> "💣"
                ":speech_balloon:" -> "💬"
                ":thought_balloon:" -> "💭"
                ":zzz:" -> "💤"
                ":wave:" -> "👋"
                ":ok_hand:" -> "👌"
                ":point_left:" -> "👈"
                ":point_right:" -> "👉"
                ":point_up_2:" -> "👆"
                ":point_down:" -> "👇"
                ":thumbsup:" -> "👍"
                ":thumbsdown:" -> "👎"
                ":punch:" -> "👊"
                ":clap:" -> "👏"
                ":raised_hands:" -> "🙌"
                ":open_hands:" -> "👐"
                ":pray:" -> "🙏"
                ":nail_care:" -> "💅"
                ":muscle:" -> "💪"
                ":ear:" -> "👂"
                ":nose:" -> "👃"
                ":eyes:" -> "👀"
                ":tongue:" -> "👅"
                ":lips:" -> "👄"
                ":baby:" -> "👶"
                ":boy:" -> "👦"
                ":girl:" -> "👧"
                ":person_with_blond_hair:" -> "👱"
                ":man:" -> "👨"
                ":woman:" -> "👩"
                ":older_man:" -> "👴"
                ":older_woman:" -> "👵"
                ":person_frowning:" -> "🙍"
                ":person_with_pouting_face:" -> "🙎"
                ":no_good:" -> "🙅"
                ":ok_woman:" -> "🙆"
                ":information_desk_person:" -> "💁"
                ":raising_hand:" -> "🙋"
                ":bow:" -> "🙇"
                ":cop:" -> "👮"
                ":guardsman:" -> "💂"
                ":construction_worker:" -> "👷"
                ":princess:" -> "👸"
                ":man_with_turban:" -> "👳"
                ":man_with_gua_pi_mao:" -> "👲"
                ":bride_with_veil:" -> "👰"
                ":angel:" -> "👼"
                ":santa:" -> "🎅"
                ":massage:" -> "💆"
                ":haircut:" -> "💇"
                ":walking:" -> "🚶"
                ":running:" -> "🏃"
                ":dancer:" -> "💃"
                ":dancers:" -> "👯"
                ":horse_racing:" -> "🏇"
                ":snowboarder:" -> "🏂"
                ":surfer:" -> "🏄"
                ":rowboat:" -> "🚣"
                ":swimmer:" -> "🏊"
                ":bicyclist:" -> "🚴"
                ":mountain_bicyclist:" -> "🚵"
                ":bath:" -> "🛀"
                ":two_women_holding_hands:" -> "👭"
                ":couple:" -> "👫"
                ":two_men_holding_hands:" -> "👬"
                ":couplekiss:" -> "💏"
                ":couple_with_heart:" -> "💑"
                ":family:" -> "👪"
                ":bust_in_silhouette:" -> "👤"
                ":busts_in_silhouette:" -> "👥"
                ":footprints:" -> "👣"
                ":monkey_face:" -> "🐵"
                ":monkey:" -> "🐒"
                ":dog:" -> "🐶"
                ":dog2:" -> "🐕"
                ":poodle:" -> "🐩"
                ":wolf:" -> "🐺"
                ":cat:" -> "🐱"
                ":cat2:" -> "🐈"
                ":tiger:" -> "🐯"
                ":tiger2:" -> "🐅"
                ":leopard:" -> "🐆"
                ":horse:" -> "🐴"
                ":racehorse:" -> "🐎"
                ":cow:" -> "🐮"
                ":ox:" -> "🐂"
                ":water_buffalo:" -> "🐃"
                ":cow2:" -> "🐄"
                ":pig:" -> "🐷"
                ":pig2:" -> "🐖"
                ":boar:" -> "🐗"
                ":pig_nose:" -> "🐽"
                ":ram:" -> "🐏"
                ":sheep:" -> "🐑"
                ":goat:" -> "🐐"
                ":dromedary_camel:" -> "🐪"
                ":camel:" -> "🐫"
                ":elephant:" -> "🐘"
                ":mouse:" -> "🐭"
                ":mouse2:" -> "🐁"
                ":rat:" -> "🐀"
                ":hamster:" -> "🐹"
                ":rabbit:" -> "🐰"
                ":rabbit2:" -> "🐇"
                ":bear:" -> "🐻"
                ":koala:" -> "🐨"
                ":panda_face:" -> "🐼"
                ":paw_prints:" -> "🐾"
                ":chicken:" -> "🐔"
                ":rooster:" -> "🐓"
                ":hatching_chick:" -> "🐣"
                ":baby_chick:" -> "🐤"
                ":hatched_chick:" -> "🐥"
                ":bird:" -> "🐦"
                ":penguin:" -> "🐧"
                ":frog:" -> "🐸"
                ":crocodile:" -> "🐊"
                ":turtle:" -> "🐢"
                ":snake:" -> "🐍"
                ":dragon_face:" -> "🐲"
                ":dragon:" -> "🐉"
                ":whale:" -> "🐳"
                ":whale2:" -> "🐋"
                ":flipper:" -> "🐬"
                ":fish:" -> "🐟"
                ":tropical_fish:" -> "🐠"
                ":blowfish:" -> "🐡"
                ":octopus:" -> "🐙"
                ":shell:" -> "🐚"
                ":snail:" -> "🐌"
                ":bug:" -> "🐛"
                ":ant:" -> "🐜"
                ":honeybee:" -> "🐝"
                ":beetle:" -> "🐞"
                ":bouquet:" -> "💐"
                ":cherry_blossom:" -> "🌸"
                ":white_flower:" -> "💮"
                ":rose:" -> "🌹"
                ":hibiscus:" -> "🌺"
                ":sunflower:" -> "🌻"
                ":blossom:" -> "🌼"
                ":tulip:" -> "🌷"
                ":seedling:" -> "🌱"
                ":evergreen_tree:" -> "🌲"
                ":deciduous_tree:" -> "🌳"
                ":palm_tree:" -> "🌴"
                ":cactus:" -> "🌵"
                ":ear_of_rice:" -> "🌾"
                ":herb:" -> "🌿"
                ":four_leaf_clover:" -> "🍀"
                ":maple_leaf:" -> "🍁"
                ":fallen_leaf:" -> "🍂"
                ":leaves:" -> "🍃"
                ":grapes:" -> "🍇"
                ":melon:" -> "🍈"
                ":watermelon:" -> "🍉"
                ":tangerine:" -> "🍊"
                ":lemon:" -> "🍋"
                ":banana:" -> "🍌"
                ":pineapple:" -> "🍍"
                ":apple:" -> "🍎"
                ":green_apple:" -> "🍏"
                ":pear:" -> "🍐"
                ":peach:" -> "🍑"
                ":cherries:" -> "🍒"
                ":strawberry:" -> "🍓"
                ":tomato:" -> "🍅"
                ":eggplant:" -> "🍆"
                ":corn:" -> "🌽"
                ":mushroom:" -> "🍄"
                ":chestnut:" -> "🌰"
                ":bread:" -> "🍞"
                ":meat_on_bone:" -> "🍖"
                ":poultry_leg:" -> "🍗"
                ":hamburger:" -> "🍔"
                ":fries:" -> "🍟"
                ":pizza:" -> "🍕"
                ":egg:" -> "🍳"
                ":stew:" -> "🍲"
                ":bento:" -> "🍱"
                ":rice_cracker:" -> "🍘"
                ":rice_ball:" -> "🍙"
                ":rice:" -> "🍚"
                ":curry:" -> "🍛"
                ":ramen:" -> "🍜"
                ":spaghetti:" -> "🍝"
                ":sweet_potato:" -> "🍠"
                ":oden:" -> "🍢"
                ":sushi:" -> "🍣"
                ":fried_shrimp:" -> "🍤"
                ":fish_cake:" -> "🍥"
                ":dango:" -> "🍡"
                ":icecream:" -> "🍦"
                ":shaved_ice:" -> "🍧"
                ":ice_cream:" -> "🍨"
                ":doughnut:" -> "🍩"
                ":cookie:" -> "🍪"
                ":birthday:" -> "🎂"
                ":cake:" -> "🍰"
                ":chocolate_bar:" -> "🍫"
                ":candy:" -> "🍬"
                ":lollipop:" -> "🍭"
                ":custard:" -> "🍮"
                ":honey_pot:" -> "🍯"
                ":baby_bottle:" -> "🍼"
                ":tea:" -> "🍵"
                ":sake:" -> "🍶"
                ":wine_glass:" -> "🍷"
                ":cocktail:" -> "🍸"
                ":tropical_drink:" -> "🍹"
                ":beer:" -> "🍺"
                ":beers:" -> "🍻"
                ":fork_and_knife:" -> "🍴"
                ":hocho:" -> "🔪"
                ":earth_africa:" -> "🌍"
                ":earth_americas:" -> "🌎"
                ":earth_asia:" -> "🌏"
                ":globe_with_meridians:" -> "🌐"
                ":japan:" -> "🗾"
                ":volcano:" -> "🌋"
                ":mount_fuji:" -> "🗻"
                ":house:" -> "🏠"
                ":house_with_garden:" -> "🏡"
                ":office:" -> "🏢"
                ":post_office:" -> "🏣"
                ":european_post_office:" -> "🏤"
                ":hospital:" -> "🏥"
                ":bank:" -> "🏦"
                ":hotel:" -> "🏨"
                ":love_hotel:" -> "🏩"
                ":convenience_store:" -> "🏪"
                ":school:" -> "🏫"
                ":department_store:" -> "🏬"
                ":factory:" -> "🏭"
                ":japanese_castle:" -> "🏯"
                ":european_castle:" -> "🏰"
                ":wedding:" -> "💒"
                ":tokyo_tower:" -> "🗼"
                ":statue_of_liberty:" -> "🗽"
                ":foggy:" -> "🌁"
                ":stars:" -> "🌃"
                ":sunrise_over_mountains:" -> "🌄"
                ":sunrise:" -> "🌅"
                ":city_sunset:" -> "🌆"
                ":city_sunrise:" -> "🌇"
                ":bridge_at_night:" -> "🌉"
                ":carousel_horse:" -> "🎠"
                ":ferris_wheel:" -> "🎡"
                ":roller_coaster:" -> "🎢"
                ":barber:" -> "💈"
                ":circus_tent:" -> "🎪"
                ":steam_locomotive:" -> "🚂"
                ":train:" -> "🚃"
                ":bullettrain_side:" -> "🚄"
                ":bullettrain_front:" -> "🚅"
                ":train2:" -> "🚆"
                ":metro:" -> "🚇"
                ":light_rail:" -> "🚈"
                ":station:" -> "🚉"
                ":tram:" -> "🚊"
                ":monorail:" -> "🚝"
                ":mountain_railway:" -> "🚞"
                ":bus:" -> "🚌"
                ":oncoming_bus:" -> "🚍"
                ":trolleybus:" -> "🚎"
                ":minibus:" -> "🚐"
                ":ambulance:" -> "🚑"
                ":fire_engine:" -> "🚒"
                ":police_car:" -> "🚓"
                ":oncoming_police_car:" -> "🚔"
                ":taxi:" -> "🚕"
                ":oncoming_taxi:" -> "🚖"
                ":red_car:" -> "🚗"
                ":oncoming_automobile:" -> "🚘"
                ":blue_car:" -> "🚙"
                ":truck:" -> "🚚"
                ":articulated_lorry:" -> "🚛"
                ":tractor:" -> "🚜"
                ":bike:" -> "🚲"
                ":busstop:" -> "🚏"
                ":rotating_light:" -> "🚨"
                ":traffic_light:" -> "🚥"
                ":vertical_traffic_light:" -> "🚦"
                ":construction:" -> "🚧"
                ":speedboat:" -> "🚤"
                ":ship:" -> "🚢"
                ":seat:" -> "💺"
                ":helicopter:" -> "🚁"
                ":suspension_railway:" -> "🚟"
                ":mountain_cableway:" -> "🚠"
                ":aerial_tramway:" -> "🚡"
                ":rocket:" -> "🚀"
                ":clock12:" -> "🕛"
                ":clock1230:" -> "🕧"
                ":clock1:" -> "🕐"
                ":clock130:" -> "🕜"
                ":clock2:" -> "🕑"
                ":clock230:" -> "🕝"
                ":clock3:" -> "🕒"
                ":clock330:" -> "🕞"
                ":clock4:" -> "🕓"
                ":clock430:" -> "🕟"
                ":clock5:" -> "🕔"
                ":clock530:" -> "🕠"
                ":clock6:" -> "🕕"
                ":clock630:" -> "🕡"
                ":clock7:" -> "🕖"
                ":clock730:" -> "🕢"
                ":clock8:" -> "🕗"
                ":clock830:" -> "🕣"
                ":clock9:" -> "🕘"
                ":clock930:" -> "🕤"
                ":clock10:" -> "🕙"
                ":clock1030:" -> "🕥"
                ":clock11:" -> "🕚"
                ":clock1130:" -> "🕦"
                ":new_moon:" -> "🌑"
                ":waxing_crescent_moon:" -> "🌒"
                ":first_quarter_moon:" -> "🌓"
                ":waxing_gibbous_moon:" -> "🌔"
                ":full_moon:" -> "🌕"
                ":waning_gibbous_moon:" -> "🌖"
                ":last_quarter_moon:" -> "🌗"
                ":waning_crescent_moon:" -> "🌘"
                ":crescent_moon:" -> "🌙"
                ":new_moon_with_face:" -> "🌚"
                ":first_quarter_moon_with_face:" -> "🌛"
                ":last_quarter_moon_with_face:" -> "🌜"
                ":full_moon_with_face:" -> "🌝"
                ":sun_with_face:" -> "🌞"
                ":star2:" -> "🌟"
                ":milky_way:" -> "🌌"
                ":cyclone:" -> "🌀"
                ":rainbow:" -> "🌈"
                ":closed_umbrella:" -> "🌂"
                ":fire:" -> "🔥"
                ":droplet:" -> "💧"
                ":ocean:" -> "🌊"
                ":jack_o_lantern:" -> "🎃"
                ":christmas_tree:" -> "🎄"
                ":fireworks:" -> "🎆"
                ":sparkler:" -> "🎇"
                ":balloon:" -> "🎈"
                ":tada:" -> "🎉"
                ":confetti_ball:" -> "🎊"
                ":tanabata_tree:" -> "🎋"
                ":bamboo:" -> "🎍"
                ":dolls:" -> "🎎"
                ":flags:" -> "🎏"
                ":wind_chime:" -> "🎐"
                ":rice_scene:" -> "🎑"
                ":ribbon:" -> "🎀"
                ":gift:" -> "🎁"
                ":ticket:" -> "🎫"
                ":trophy:" -> "🏆"
                ":basketball:" -> "🏀"
                ":football:" -> "🏈"
                ":rugby_football:" -> "🏉"
                ":tennis:" -> "🎾"
                ":bowling:" -> "🎳"
                ":fishing_pole_and_fish:" -> "🎣"
                ":running_shirt_with_sash:" -> "🎽"
                ":ski:" -> "🎿"
                ":dart:" -> "🎯"
                ":8ball:" -> "🎱"
                ":crystal_ball:" -> "🔮"
                ":video_game:" -> "🎮"
                ":slot_machine:" -> "🎰"
                ":game_die:" -> "🎲"
                ":black_joker:" -> "🃏"
                ":mahjong:" -> "🀄"
                ":flower_playing_cards:" -> "🎴"
                ":performing_arts:" -> "🎭"
                ":art:" -> "🎨"
                ":eyeglasses:" -> "👓"
                ":necktie:" -> "👔"
                ":tshirt:" -> "👕"
                ":jeans:" -> "👖"
                ":dress:" -> "👗"
                ":kimono:" -> "👘"
                ":bikini:" -> "👙"
                ":womans_clothes:" -> "👚"
                ":purse:" -> "👛"
                ":handbag:" -> "👜"
                ":pouch:" -> "👝"
                ":school_satchel:" -> "🎒"
                ":shoe:" -> "👞"
                ":athletic_shoe:" -> "👟"
                ":high_heel:" -> "👠"
                ":sandal:" -> "👡"
                ":boot:" -> "👢"
                ":crown:" -> "👑"
                ":womans_hat:" -> "👒"
                ":tophat:" -> "🎩"
                ":mortar_board:" -> "🎓"
                ":lipstick:" -> "💄"
                ":ring:" -> "💍"
                ":gem:" -> "💎"
                ":mute:" -> "🔇"
                ":sound:" -> "🔉"
                ":speaker:" -> "🔊"
                ":loudspeaker:" -> "📢"
                ":mega:" -> "📣"
                ":postal_horn:" -> "📯"
                ":bell:" -> "🔔"
                ":no_bell:" -> "🔕"
                ":musical_score:" -> "🎼"
                ":musical_note:" -> "🎵"
                ":notes:" -> "🎶"
                ":microphone:" -> "🎤"
                ":headphones:" -> "🎧"
                ":radio:" -> "📻"
                ":saxophone:" -> "🎷"
                ":guitar:" -> "🎸"
                ":musical_keyboard:" -> "🎹"
                ":trumpet:" -> "🎺"
                ":violin:" -> "🎻"
                ":iphone:" -> "📱"
                ":calling:" -> "📲"
                ":telephone_receiver:" -> "📞"
                ":pager:" -> "📟"
                ":fax:" -> "📠"
                ":battery:" -> "🔋"
                ":electric_plug:" -> "🔌"
                ":computer:" -> "💻"
                ":minidisc:" -> "💽"
                ":floppy_disk:" -> "💾"
                ":cd:" -> "💿"
                ":dvd:" -> "📀"
                ":movie_camera:" -> "🎥"
                ":clapper:" -> "🎬"
                ":tv:" -> "📺"
                ":camera:" -> "📷"
                ":video_camera:" -> "📹"
                ":vhs:" -> "📼"
                ":mag:" -> "🔍"
                ":mag_right:" -> "🔎"
                ":bulb:" -> "💡"
                ":flashlight:" -> "🔦"
                ":lantern:" -> "🏮"
                ":notebook_with_decorative_cover:" -> "📔"
                ":closed_book:" -> "📕"
                ":open_book:" -> "📖"
                ":green_book:" -> "📗"
                ":blue_book:" -> "📘"
                ":orange_book:" -> "📙"
                ":books:" -> "📚"
                ":notebook:" -> "📓"
                ":ledger:" -> "📒"
                ":page_with_curl:" -> "📃"
                ":scroll:" -> "📜"
                ":page_facing_up:" -> "📄"
                ":newspaper:" -> "📰"
                ":bookmark_tabs:" -> "📑"
                ":bookmark:" -> "🔖"
                ":moneybag:" -> "💰"
                ":yen:" -> "💴"
                ":dollar:" -> "💵"
                ":euro:" -> "💶"
                ":pound:" -> "💷"
                ":money_with_wings:" -> "💸"
                ":credit_card:" -> "💳"
                ":chart:" -> "💹"
                ":e-mail:" -> "📧"
                ":incoming_envelope:" -> "📨"
                ":envelope_with_arrow:" -> "📩"
                ":outbox_tray:" -> "📤"
                ":inbox_tray:" -> "📥"
                ":package:" -> "📦"
                ":mailbox:" -> "📫"
                ":mailbox_closed:" -> "📪"
                ":mailbox_with_mail:" -> "📬"
                ":mailbox_with_no_mail:" -> "📭"
                ":postbox:" -> "📮"
                ":pencil:" -> "📝"
                ":briefcase:" -> "💼"
                ":file_folder:" -> "📁"
                ":open_file_folder:" -> "📂"
                ":date:" -> "📅"
                ":calendar:" -> "📆"
                ":card_index:" -> "📇"
                ":chart_with_upwards_trend:" -> "📈"
                ":chart_with_downwards_trend:" -> "📉"
                ":bar_chart:" -> "📊"
                ":clipboard:" -> "📋"
                ":pushpin:" -> "📌"
                ":round_pushpin:" -> "📍"
                ":paperclip:" -> "📎"
                ":straight_ruler:" -> "📏"
                ":triangular_ruler:" -> "📐"
                ":lock:" -> "🔒"
                ":lock_with_ink_pen:" -> "🔏"
                ":closed_lock_with_key:" -> "🔐"
                ":key:" -> "🔑"
                ":hammer:" -> "🔨"
                ":gun:" -> "🔫"
                ":wrench:" -> "🔧"
                ":nut_and_bolt:" -> "🔩"
                ":link:" -> "🔗"
                ":microscope:" -> "🔬"
                ":telescope:" -> "🔭"
                ":satellite:" -> "📡"
                ":syringe:" -> "💉"
                ":pill:" -> "💊"
                ":door:" -> "🚪"
                ":toilet:" -> "🚽"
                ":shower:" -> "🚿"
                ":bathtub:" -> "🛁"
                ":smoking:" -> "🚬"
                ":moyai:" -> "🗿"
                ":atm:" -> "🏧"
                ":put_litter_in_its_place:" -> "🚮"
                ":potable_water:" -> "🚰"
                ":mens:" -> "🚹"
                ":womens:" -> "🚺"
                ":restroom:" -> "🚻"
                ":baby_symbol:" -> "🚼"
                ":wc:" -> "🚾"
                ":passport_control:" -> "🛂"
                ":customs:" -> "🛃"
                ":baggage_claim:" -> "🛄"
                ":left_luggage:" -> "🛅"
                ":children_crossing:" -> "🚸"
                ":no_entry_sign:" -> "🚫"
                ":no_bicycles:" -> "🚳"
                ":no_smoking:" -> "🚭"
                ":do_not_litter:" -> "🚯"
                ":non-potable_water:" -> "🚱"
                ":no_pedestrians:" -> "🚷"
                ":no_mobile_phones:" -> "📵"
                ":underage:" -> "🔞"
                ":arrows_clockwise:" -> "🔃"
                ":arrows_counterclockwise:" -> "🔄"
                ":back:" -> "🔙"
                ":end:" -> "🔚"
                ":on:" -> "🔛"
                ":soon:" -> "🔜"
                ":top:" -> "🔝"
                ":six_pointed_star:" -> "🔯"
                ":twisted_rightwards_arrows:" -> "🔀"
                ":repeat:" -> "🔁"
                ":repeat_one:" -> "🔂"
                ":arrow_up_small:" -> "🔼"
                ":arrow_down_small:" -> "🔽"
                ":cinema:" -> "🎦"
                ":low_brightness:" -> "🔅"
                ":high_brightness:" -> "🔆"
                ":signal_strength:" -> "📶"
                ":vibration_mode:" -> "📳"
                ":mobile_phone_off:" -> "📴"
                ":currency_exchange:" -> "💱"
                ":heavy_dollar_sign:" -> "💲"
                ":trident:" -> "🔱"
                ":name_badge:" -> "📛"
                ":beginner:" -> "🔰"
                ":keycap_ten:" -> "🔟"
                ":capital_abcd:" -> "🔠"
                ":abcd:" -> "🔡"
                ":1234:" -> "🔢"
                ":symbols:" -> "🔣"
                ":abc:" -> "🔤"
                ":ab:" -> "🆎"
                ":cl:" -> "🆑"
                ":cool:" -> "🆒"
                ":free:" -> "🆓"
                ":id:" -> "🆔"
                ":new:" -> "🆕"
                ":ng:" -> "🆖"
                ":ok:" -> "🆗"
                ":sos:" -> "🆘"
                ":up:" -> "🆙"
                ":vs:" -> "🆚"
                ":koko:" -> "🈁"
                ":ideograph_advantage:" -> "🉐"
                ":accept:" -> "🉑"
                ":red_circle:" -> "🔴"
                ":large_blue_circle:" -> "🔵"
                ":large_orange_diamond:" -> "🔶"
                ":large_blue_diamond:" -> "🔷"
                ":small_orange_diamond:" -> "🔸"
                ":small_blue_diamond:" -> "🔹"
                ":small_red_triangle:" -> "🔺"
                ":small_red_triangle_down:" -> "🔻"
                ":diamond_shape_with_a_dot_inside:" -> "💠"
                ":radio_button:" -> "🔘"
                ":white_square_button:" -> "🔳"
                ":black_square_button:" -> "🔲"
                ":checkered_flag:" -> "🏁"
                ":triangular_flag_on_post:" -> "🚩"
                ":crossed_flags:" -> "🎌"
                else -> throw IllegalArgumentException("'$name' is not a valid name")
            }
        }
    }
}
