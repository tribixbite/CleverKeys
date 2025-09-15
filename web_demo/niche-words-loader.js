/**
 * Niche Words Loader
 * Loads and processes niche words from the Python vocabulary file
 */

// Convert the niche_words.py content to JavaScript
const NICHE_WORDS = {
    internetSlang: [
        'lol', 'lmao', 'rofl', 'brb', 'omg', 'wtf', 'imo', 'imho', 'smh', 'tbh', 
        'irl', 'afk', 'fyi', 'ikr', 'nvm', 'thx', 'pls', 'dm', 'pm', 'ama',
        'eli5', 'tl;dr', 'ftfy', 'afaik', 'iirc', 'yolo', 'fomo', 'bae', 'lit', 'salty',
        'savage', 'woke', 'ghosting', 'flexing', 'cancelled', 'simp', 'stan', 'vibe', 'mood', 'lowkey',
        'highkey', 'deadass', 'fam', 'bruh', 'yeet', 'oof', 'sus', 'cap', 'bussin', 'sheesh',
        'periodt', 'slay', 'tea', 'shade', 'receipts', 'clout', 'drip', 'flex', 'glow', 'goat',
        'hits', 'slaps', 'bops', 'snack', 'thicc', 'thirsty', 'triggered', 'trolling', 'viral', 'wholesome',
        'adulting', 'binge', 'clickbait', 'cringe', 'epic', 'fail', 'facepalm', 'feelsbadman', 'feelsgoodman', 'gg',
        'glhf', 'hype', 'inspo', 'jelly', 'karen', 'meme', 'noob', 'normie', 'op', 'poggers',
        'pwned', 'rekt', 'selfie', 'ship', 'shook', 'sickening', 'spam', 'squad', 'swag', 'swol',
        'thot', 'troll', 'turnt', 'unfriend', 'unfollow', 'uwu', 'weeb', 'wig', 'yass', 'zaddy'
    ],
    
    techTerms: [
        'api', 'gpu', 'cpu', 'ram', 'ssd', 'hdd', 'html', 'css', 'json', 'xml',
        'sql', 'nosql', 'gui', 'cli', 'ide', 'sdk', 'cdn', 'dns', 'vpn', 'ssl',
        'http', 'https', 'ftp', 'ssh', 'tcp', 'udp', 'ip', 'url', 'uri', 'ux',
        'ui', 'ai', 'ml', 'dl', 'nlp', 'cv', 'ar', 'vr', 'xr', 'iot',
        'blockchain', 'crypto', 'bitcoin', 'ethereum', 'nft', 'defi', 'dao', 'web3', 'metaverse', 'quantum',
        'kubernetes', 'docker', 'microservices', 'serverless', 'lambda', 'terraform', 'ansible', 'jenkins', 'git', 'github',
        'gitlab', 'bitbucket', 'jira', 'confluence', 'slack', 'zoom', 'teams', 'discord', 'twitch', 'oauth',
        'jwt', 'cors', 'ajax', 'websocket', 'graphql', 'rest', 'soap', 'grpc', 'mqtt', 'amqp',
        'kafka', 'rabbitmq', 'redis', 'mongodb', 'postgresql', 'mysql', 'oracle', 'elasticsearch', 'kibana', 'grafana',
        'prometheus', 'datadog', 'splunk', 'terraform', 'cloudformation', 'azure', 'gcp', 'aws', 'heroku', 'netlify',
        'vercel', 'firebase', 'supabase', 'auth0', 'okta', 'stripe', 'paypal', 'shopify', 'wordpress', 'drupal',
        'joomla', 'magento', 'woocommerce', 'squarespace', 'wix', 'webflow', 'figma', 'sketch', 'adobe', 'canva',
        'nodejs', 'reactjs', 'vuejs', 'angular', 'svelte', 'nextjs', 'nuxtjs', 'gatsby', 'webpack', 'vite',
        'typescript', 'javascript', 'python', 'java', 'csharp', 'cpp', 'golang', 'rust', 'kotlin', 'swift',
        'dart', 'flutter', 'reactnative', 'xamarin', 'ionic', 'electron', 'tauri', 'pwa', 'spa', 'ssr',
        'ssg', 'jamstack', 'headless', 'cms', 'crm', 'erp', 'scrum', 'agile', 'kanban', 'devops',
        'cicd', 'tdd', 'bdd', 'unittest', 'jest', 'mocha', 'cypress', 'selenium', 'puppeteer', 'playwright'
    ],
    
    businessAbbr: [
        'roi', 'kpi', 'b2b', 'b2c', 'saas', 'paas', 'iaas', 'crm', 'erp', 'hr',
        'ceo', 'cto', 'cfo', 'coo', 'cmo', 'vp', 'svp', 'evp', 'hr', 'it',
        'qa', 'qc', 'r&d', 'pr', 'seo', 'sem', 'ppc', 'cpc', 'cpm', 'ctr',
        'cvr', 'cac', 'ltv', 'mrr', 'arr', 'churn', 'nps', 'csat', 'sla', 'kpi',
        'okr', 'swot', 'pest', 'usp', 'mvp', 'poc', 'rfp', 'rfq', 'rfi', 'sow',
        'mou', 'nda', 'ip', 'ipo', 'ma', 'pe', 'vc', 'lbo', 'ebitda', 'capex',
        'opex', 'cogs', 'gross', 'net', 'ebit', 'ebt', 'eps', 'pe', 'ps', 'pb',
        'roe', 'roa', 'roi', 'irr', 'npv', 'dcf', 'wacc', 'capm', 'beta', 'alpha',
        'etf', 'reit', 'cd', 'apy', 'apr', 'atm', 'kyc', 'aml', 'gdpr', 'ccpa',
        'sox', 'hipaa', 'pci', 'iso', 'gaap', 'ifrs', 'fasb', 'sec', 'ftc', 'fcc'
    ]
};

// Function to process app names (convert CamelCase to lowercase, handle compound words)
function processAppNames() {
    const rawApps = [
        'Facebook', 'Instagram', 'WhatsApp', 'Messenger', 'Twitter', 'TikTok', 'Snapchat', 'Pinterest',
        'LinkedIn', 'Reddit', 'Discord', 'Telegram', 'Signal', 'Viber', 'WeChat', 'Line', 'Kakao',
        'YouTube', 'Netflix', 'Spotify', 'AppleMusic', 'AmazonPrime', 'DisneyPlus', 'Hulu', 'HBO',
        'Twitch', 'Vimeo', 'SoundCloud', 'Pandora', 'Deezer', 'Tidal', 'Audible', 'Kindle',
        'GoogleDrive', 'Dropbox', 'OneDrive', 'iCloud', 'Box', 'Mega', 'pCloud', 'Tresorit',
        'Evernote', 'Notion', 'Obsidian', 'Roam', 'OneNote', 'Bear', 'Todoist', 'Trello',
        'Asana', 'Monday', 'ClickUp', 'Jira', 'Basecamp', 'Airtable', 'Coda', 'Slack',
        'Teams', 'Zoom', 'Skype', 'WebEx', 'GoToMeeting', 'BlueJeans', 'Whereby', 'Jitsi',
        'Gmail', 'Outlook', 'ProtonMail', 'Tutanota', 'FastMail', 'Hey', 'Spark', 'Newton',
        'Chrome', 'Firefox', 'Safari', 'Edge', 'Opera', 'Brave', 'Vivaldi', 'Tor',
        'Photoshop', 'Illustrator', 'Premiere', 'AfterEffects', 'Lightroom', 'InDesign', 'XD', 'Figma',
        'Sketch', 'Canva', 'Procreate', 'Affinity', 'GIMP', 'Inkscape', 'Blender', 'Maya',
        'Unity', 'Unreal', 'Godot', 'GameMaker', 'Construct', 'RPGMaker', 'Roblox', 'Minecraft',
        'Fortnite', 'Valorant', 'LeagueOfLegends', 'Overwatch', 'ApexLegends', 'CallOfDuty', 'GTA', 'FIFA',
        'Amazon', 'eBay', 'Alibaba', 'Etsy', 'Shopify', 'WooCommerce', 'Magento', 'BigCommerce',
        'PayPal', 'Venmo', 'CashApp', 'Zelle', 'Stripe', 'Square', 'Wise', 'Revolut',
        'Robinhood', 'Coinbase', 'Binance', 'Kraken', 'eToro', 'TD', 'Fidelity', 'Vanguard',
        'Uber', 'Lyft', 'Grab', 'Ola', 'Didi', 'Bolt', 'Cabify', 'Gett',
        'Airbnb', 'Booking', 'Expedia', 'Hotels', 'Agoda', 'Trivago', 'Kayak', 'Skyscanner',
        'DoorDash', 'UberEats', 'Grubhub', 'Postmates', 'Deliveroo', 'JustEat', 'Zomato', 'Swiggy',
        'Tinder', 'Bumble', 'Hinge', 'OkCupid', 'Match', 'eHarmony', 'PlentyOfFish', 'Badoo',
        'Duolingo', 'Babbel', 'Rosetta', 'Busuu', 'Memrise', 'Anki', 'Quizlet', 'Khan',
        'Coursera', 'Udemy', 'edX', 'Udacity', 'Pluralsight', 'LinkedIn', 'Skillshare', 'MasterClass',
        'Fitbit', 'Strava', 'MyFitnessPal', 'Nike', 'Adidas', 'Peloton', 'Zwift', 'Calm',
        'Headspace', 'Insight', 'TenPercent', 'Waking', 'Balance', 'Sanvello', 'Youper', 'Replika'
    ];
    
    const processed = [];
    rawApps.forEach(app => {
        // Convert to lowercase
        const lower = app.toLowerCase();
        processed.push(lower);
        
        // Also add common variations
        // Split CamelCase: AppleMusic -> apple, music
        const parts = app.match(/[A-Z][a-z]+/g);
        if (parts && parts.length > 1) {
            parts.forEach(part => {
                const partLower = part.toLowerCase();
                if (partLower.length > 3) { // Only add meaningful parts
                    processed.push(partLower);
                }
            });
        }
    });
    
    return [...new Set(processed)]; // Remove duplicates
}

// Combine all words
function getAllNicheWords() {
    const allWords = [
        ...NICHE_WORDS.internetSlang,
        ...NICHE_WORDS.techTerms,
        ...NICHE_WORDS.businessAbbr,
        ...processAppNames()
    ];
    
    return [...new Set(allWords)]; // Remove any duplicates
}

// Export for use in other scripts
if (typeof module !== 'undefined' && module.exports) {
    module.exports = { NICHE_WORDS, processAppNames, getAllNicheWords };
}