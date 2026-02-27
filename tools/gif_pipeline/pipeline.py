#!/usr/bin/env python3
"""
pipeline.py — Continuous GIF acquisition pipeline.

Runs hourly cycles: download → convert → optimize → cleanup.
Targets 500K GIFs from Giphy (26,500/category × 17 + 50K trending).
Resumes from download_history.json on restart. Deletes raw GIFs only
after confirmed optimization (output >10KB).

Usage:
    python pipeline.py                    # Full 500K pipeline
    python pipeline.py --test             # Test mode: 50 GIFs, 1 cycle
    python pipeline.py --cycles 1         # Single cycle then exit
    python pipeline.py --target 100000    # Custom target

Rate limit: 100 API calls/hr (beta key), 50 results/call = 5000 GIFs/hr max.
"""

import argparse
import json
import logging
import os
import shutil
import subprocess
import sys
import time
from datetime import datetime, timedelta
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple

import requests
from dotenv import load_dotenv

# ── Configuration ───────────────────────────────────────────────────────────

CATEGORIES: List[Dict] = [
    {"name": "happy", "queries": [
        "happy", "joy", "smile", "cheerful", "grinning", "yay", "celebrate", "dancing happy",
        "good vibes", "feeling good", "woohoo", "beaming", "joyful", "bliss", "elated",
        "overjoyed", "delighted", "pleased", "content", "grateful", "thankful", "blessed",
        "satisfied", "grin", "euphoria", "ecstasy", "party", "sparkle", "rainbow",
        "sunshine happy", "warm fuzzy", "good news", "positive vibes",
    ]},
    {"name": "funny", "queries": [
        "funny", "lol", "hilarious", "comedy", "joke", "humor", "silly", "goofy",
        "ridiculous", "meme", "wtf funny", "dying laughing", "absurd", "prank", "satire",
        "witty", "sarcastic", "ironic", "wacky", "dorky", "nerdy", "clown", "tease",
        "oops funny", "blooper", "fail compilation", "plot twist funny", "bruh moment",
        "cursed image", "sus", "no cap", "shenanigans",
    ]},
    {"name": "love", "queries": [
        "love", "heart", "kiss", "romance", "crush", "i love you", "hug", "cuddle",
        "adore", "blowing kiss", "heart eyes", "smitten", "couple", "affection", "sweetheart",
        "devotion", "flirt", "wink", "valentine", "passion", "soulmate", "babe", "darling",
        "honey", "cherish", "embrace", "tender", "romantic", "love letter", "proposal",
        "first kiss", "butterflies", "swoon",
    ]},
    {"name": "sad", "queries": [
        "sad", "crying", "tears", "depressed", "heartbroken", "sobbing", "miserable", "upset",
        "bawling", "disappointed", "feeling down", "lonely", "grief", "melancholy", "weeping",
        "bummed", "gloomy", "somber", "mourn", "devastated", "sorrowful", "broken heart",
        "pain", "hurt", "emo", "blue", "unhappy", "rain sad", "goodbye sad",
        "miss you", "alone", "left out",
    ]},
    {"name": "angry", "queries": [
        "angry", "rage", "furious", "mad", "pissed off", "frustrated", "livid", "tantrum",
        "fuming", "irritated", "outraged", "seething", "hostile", "enraged", "grumpy", "bitter",
        "resentful", "annoyed", "fury", "irate", "fire angry", "steam", "exploding",
        "table flip", "punch", "destroy", "smash", "hulk", "triggered",
        "rant", "fed up", "done with this",
    ]},
    {"name": "surprised", "queries": [
        "surprised", "shocked", "omg", "what", "jaw drop", "mind blown", "plot twist",
        "no way", "gasping", "stunned", "unexpected", "shook", "speechless", "disbelief",
        "astonished", "flabbergasted", "double take", "wide eyes", "wow", "exploding head",
        "whoa", "holy cow", "say what", "wait what", "excuse me", "unbelievable",
        "did that just happen", "clutching pearls",
    ]},
    {"name": "excited", "queries": [
        "excited", "hyped", "pumped", "stoked", "thrilled", "ecstatic", "jumping", "lets go",
        "cant wait", "fired up", "amped", "woo", "anticipation", "eager", "energetic",
        "psyched", "buzzing", "ready", "dance", "bouncing", "fist pump", "adrenaline",
        "star eyes", "sparkling", "magic", "confetti", "fireworks", "turnt",
        "lit", "sending it", "full send",
    ]},
    {"name": "scared", "queries": [
        "scared", "terrified", "horror", "fear", "nightmare", "spooky", "creepy", "haunted",
        "frightened", "panic", "screaming", "run away", "anxiety", "startled", "eerie",
        "dread", "phobia", "worried", "ghost", "zombie", "vampire", "skeleton", "skull",
        "devil", "monster", "scream", "hide", "peek", "nervous wreck",
        "fight or flight", "cold sweat", "shaking",
    ]},
    {"name": "disgusted", "queries": [
        "disgusted", "gross", "ew", "nope", "nasty", "yuck", "revolting", "cringing",
        "vomit", "eww", "thats gross", "no thanks", "repulsed", "queasy", "gag",
        "unappetizing", "abhorrent", "sick", "trash", "garbage", "poop", "stink",
        "rotten", "foul", "nauseating", "blegh", "hard pass",
    ]},
    {"name": "awkward", "queries": [
        "awkward", "cringe", "embarrassed", "uncomfortable", "yikes", "ooh awkward",
        "nervous", "side eye", "sweating", "crickets", "tumbleweed", "dead inside",
        "secondhand embarrassment", "flustered", "self conscious", "sheepish", "blush",
        "deer headlights", "eyeroll", "shrug", "confused", "dizzy", "bored",
        "slow blink", "internal screaming", "this is fine", "send help",
    ]},
    {"name": "proud", "queries": [
        "proud", "achievement", "victory", "winning", "champion", "nailed it", "flex",
        "boss", "accomplished", "crushed it", "legend", "like a boss", "swagger",
        "strutting", "bowing", "triumphant", "glory", "dominating", "strong", "star",
        "medal", "trophy", "crown", "king", "queen", "first place",
        "leveled up", "goat", "unstoppable",
    ]},
    {"name": "relieved", "queries": [
        "relieved", "phew", "calm", "finally", "thank god", "relaxed", "exhale",
        "weight off", "close call", "made it", "deep breath", "safe", "sigh of relief",
        "survived", "at peace", "tranquil", "serene", "unwinding", "sleep", "tired",
        "spa", "zen", "meditation", "grateful relief", "bullet dodged",
        "crisis averted", "rest easy",
    ]},
    {"name": "smug", "queries": [
        "smug", "sassy", "confident", "smirk", "told you so", "deal with it",
        "sunglasses", "mic drop", "unbothered", "flipping hair", "whatever", "too cool",
        "cocky", "superior", "know it all", "self satisfied", "gloating", "nonchalant",
        "snicker", "eyebrow raise", "cool guy", "rich", "money", "flex on them",
        "walk away cool", "not my problem", "unimpressed",
    ]},
    {"name": "sorry", "queries": [
        "sorry", "apologize", "oops", "my bad", "forgive me", "regret", "apology",
        "whoops", "mistake", "i messed up", "please forgive", "guilt", "remorse",
        "contrite", "making amends", "begging", "pleading", "puppy eyes", "beg",
        "pray forgiveness", "grovel", "peace offering", "wave sorry",
        "bowing apology", "flowers apology",
    ]},
    {"name": "ashamed", "queries": [
        "ashamed", "shame", "facepalm", "hiding", "mortified", "walk of shame", "fail",
        "epic fail", "embarrassing", "cringe fail", "face palm", "bury head",
        "humiliated", "disgraced", "fumble", "blunder", "flop", "disaster",
        "shrug shame", "caught red handed", "exposed", "busted", "walk of shame",
        "oof", "bruh fail", "that escalated",
    ]},
    {"name": "approve", "queries": [
        "thumbs up", "approve", "yes", "agree", "nodding", "clapping", "well done",
        "bravo", "nice", "good job", "you got this", "correct", "high five", "cheering",
        "salute", "toast", "standing ovation", "encore", "nod", "wave",
        "support", "affirm", "valid", "accepted", "great work", "kudos",
        "round of applause", "respect",
    ]},
    {"name": "laugh", "queries": [
        "laugh", "rofl", "haha", "lmao", "dying", "cackling", "snort laugh",
        "belly laugh", "cant stop laughing", "wheeze", "giggling", "hysterical",
        "chuckling", "cracking up", "laughing crying", "howling", "snickering", "guffaw",
        "giggle", "tee hee", "rolling on floor", "dead laughing", "spit take",
        "comedy gold", "laugh track", "too funny",
    ]},
]

# Top 2000 most popular/universal reaction GIF search terms — these yield the
# highest-engagement results on Giphy and represent the GIFs "everyone knows".
# Searched separately with a dedicated budget to ensure coverage.
TOP_POPULAR_QUERIES: List[str] = [
    "reaction", "reaction gif", "mood", "vibe", "same", "me when", "relatable",
    "thank you", "goodbye", "hello", "good morning", "good night", "congratulations",
    "happy birthday", "merry christmas", "new year", "welcome", "miss you",
    "thinking of you", "get well soon", "best friends", "bff", "squad goals",
    "no", "yes", "maybe", "idk", "whatever", "seriously", "really", "bruh",
    "ok", "sure", "fine", "nah", "yep", "absolutely", "definitely", "obviously",
    "eye roll", "side eye", "death stare", "blank stare", "poker face", "resting face",
    "waiting", "bored", "impatient", "hurry up", "tick tock", "any day now",
    "mind blown", "shook", "dead", "im dead", "i cant", "crying laughing",
    "slay", "queen", "king", "boss", "legend", "icon", "goat",
    "bye", "peace out", "see ya", "later", "gotta go", "im out", "deuces",
    "food", "hungry", "snack", "eating", "pizza", "coffee", "wine", "beer", "cheers",
    "cat", "dog", "puppy", "kitten", "cute animal", "baby animal",
    "anime reaction", "anime happy", "anime sad", "anime angry", "anime love",
    "celebrity reaction", "movie reaction", "tv show reaction",
    "sports celebration", "touchdown", "goal", "slam dunk", "home run",
    "office reaction", "the office", "parks and rec", "brooklyn nine nine",
    "marvel", "star wars", "harry potter", "disney", "pixar",
    "baby yoda", "minions", "spongebob", "simpsons", "friends tv",
    "drake", "beyonce", "taylor swift", "oprah", "keanu reeves",
    "michael scott", "dwight schrute", "joey tribbiani", "chandler bing",
]

# Viral meme GIFs — specific searches for the most shared/recognized GIFs
VIRAL_MEME_QUERIES: List[str] = [
    # Classic viral memes
    "confused math lady", "blinking white guy", "surprised pikachu", "crying jordan",
    "this is fine dog", "disappointed cricket fan", "roll safe think about it",
    "obama mic drop", "kermit tea", "hide the pain harold", "distracted boyfriend",
    "woman yelling at cat", "two buttons", "change my mind", "drake hotline bling",
    "expanding brain", "galaxy brain", "is this a pigeon", "they dont know",
    "uno draw 25", "always has been", "panik kalm", "gru plan", "trade offer",
    # Reaction classics
    "slow clap", "standing ovation", "golf clap", "sarcastic clap",
    "mic drop", "drop the mic", "boom", "shots fired", "roasted",
    "savage", "oof", "big oof", "yikes", "cringe", "secondhand embarrassment",
    "nervous sweating", "sweating bullets", "awkward look", "side eye chloe",
    "conceited reaction", "really though", "seriously", "you serious",
    # TV/Movie iconic moments
    "im out seinfeld", "thats what she said", "thats a bold strategy",
    "well well well", "how the turntables", "why are you the way that you are",
    "i declare bankruptcy", "no god please no", "toby why", "prison mike",
    "leslie knope", "ron swanson", "treat yo self", "everything hurts",
    "jake peralta", "cool cool cool", "noice", "toit", "terry loves",
    "joey how you doin", "pivot", "we were on a break", "unagi",
    "abed community", "troy and abed", "britta the worst", "streets ahead",
    # Emotions - specific expressions
    "jim halpert face", "pam beesly", "stanley eye roll", "kevin chili",
    "creed bratton", "andy bernard", "angela scoff", "oscar actually",
    "phyllis shade", "kelly kapoor", "ryan fire guy", "meredith party",
    # Sports/celebration
    "lebron celebration", "steph curry shimmy", "gronk spike",
    "cristiano ronaldo celebration", "messi celebration", "tiger woods fist pump",
    "aaron rodgers belt", "cam newton dab", "usain bolt pose",
    # Internet culture
    "take my money", "shut up and take my money", "invest", "stonks",
    "to the moon", "diamond hands", "paper hands", "apes together strong",
    "its something", "its honest work", "modern problems", "outstanding move",
    "big brain time", "sneak 100", "destruction 100", "speech 100",
    "they had us first half", "visible confusion", "impossible thanos",
    "reality can be whatever", "perfectly balanced", "i am inevitable",
    # Cute/wholesome
    "seal of approval", "cat approve", "dog approve", "thumbs up kid",
    "proud of you", "you can do it", "believe in you", "sending love",
    "virtual hug", "group hug", "bear hug", "wholesome", "faith in humanity",
    # Sassy/unbothered
    "hair flip", "sassy", "fab", "fierce", "werk", "slay queen",
    "bye felicia", "girl bye", "boy bye", "talk to the hand",
    "not today satan", "the audacity", "the nerve", "the lion the audacity",
]

# Top 500 universal search terms — comprehensive coverage of what people actually search
TOP_500_UNIVERSAL: List[str] = [
    # ── Tenor Reaction Categories (official) ────────────────────────────────
    "no", "lol", "excited", "bye", "sorry", "congratulations", "sleepy", "hello",
    "hugs", "ok", "please", "thank you", "miss you", "wink", "whatever", "hungry",
    "dance", "annoyed", "omg", "crazy", "shrug", "smile", "awkward", "ew", "angry",
    "surprised", "why", "thumbs up", "wow", "ouch", "oops", "youre welcome", "lazy",
    "stressed", "embarrassed", "clapping", "awesome", "jk", "good luck", "high five",
    "nervous", "duh", "aww", "scared", "bored", "sigh", "kiss", "sad", "good night",
    "good morning", "confused", "chill out", "love", "happy", "cry", "yes",

    # ── GIPHY Reaction Categories ────────────────────────────────────────────
    "abandon thread", "agree", "amused", "applause", "aroused", "burn",
    "cool story bro", "deal with it", "do not want", "drunk", "eww", "finger guns",
    "fist bump", "flirt", "fml", "gtfo", "hate", "haha", "hairflip", "handshake",
    "happy dance", "help", "hmm", "idk", "jealous", "judging", "kiss blow",
    "lmao", "mmhmm", "nailed it", "nope", "oh snap", "oh really", "party hard",
    "popcorn", "praying", "rolling eyes", "salute", "scream", "seriously",
    "slow clap", "smh", "snort", "squint", "stare", "suspicious", "swerve",
    "thinking", "ugh", "unimpressed", "wait what", "well then", "yawn", "yolo",

    # ── GIPHY Emotion Categories ─────────────────────────────────────────────
    "frustrated", "inspired", "lonely", "pain", "relaxed", "sassy", "sick",
    "suspicious", "tired", "hopeful", "anxious", "grateful", "jealous",
    "nostalgic", "peaceful", "playful", "romantic", "silly", "sympathetic",

    # ── Common Phrases & Expressions ─────────────────────────────────────────
    "notes", "taking notes", "noted", "write that down", "remember", "dont forget",
    "pay attention", "listen here", "hear me out", "let me explain", "actually",
    "well actually", "um actually", "correction", "excuse me", "pardon",
    "come again", "say again", "repeat that", "what did you say", "hold up",
    "wait a minute", "one sec", "brb", "afk", "on my way", "running late",
    "almost there", "here i come", "coming", "be right there", "where are you",
    "looking for", "searching", "found it", "got it", "eureka", "aha",

    # ── TV Shows & Reality TV ────────────────────────────────────────────────
    "shark tank", "mark cuban", "mr wonderful", "kevin oleary", "lori greiner",
    "daymond john", "barbara corcoran", "im out shark tank", "deal shark tank",
    "real housewives", "kardashians", "keeping up", "jersey shore", "the bachelor",
    "survivor", "big brother", "rupaul", "drag race", "queer eye", "fab five",
    "gordon ramsay", "hells kitchen", "masterchef", "its raw", "idiot sandwich",
    "judge judy", "maury", "you are the father", "jerry springer", "steve harvey",
    "family feud", "wheel of fortune", "jeopardy", "ellen", "jimmy fallon",
    "jimmy kimmel", "conan", "colbert", "snl", "saturday night live",

    # ── Movies & Franchises ──────────────────────────────────────────────────
    "avengers", "iron man", "thor", "captain america", "hulk smash", "black widow",
    "spiderman", "doctor strange", "black panther", "thanos", "snap", "infinity",
    "batman", "joker", "why so serious", "im batman", "gotham", "superman",
    "wonder woman", "aquaman", "justice league", "fast and furious", "family",
    "john wick", "matrix", "red pill blue pill", "there is no spoon",
    "lord of the rings", "gandalf", "you shall not pass", "my precious", "frodo",
    "star trek", "live long prosper", "beam me up", "make it so", "resistance futile",
    "jurassic park", "clever girl", "life finds a way", "hold onto your butts",
    "titanic", "im the king", "ill never let go", "draw me like",
    "princess bride", "as you wish", "inconceivable", "my name is inigo",

    # ── Sports & Athletes ────────────────────────────────────────────────────
    "touchdown", "goal", "slam dunk", "home run", "strike", "hole in one",
    "world cup", "super bowl", "nba finals", "world series", "olympics",
    "michael jordan", "crying jordan", "shrug jordan", "lebron james",
    "tom brady", "patrick mahomes", "steph curry", "kobe", "mamba mentality",
    "messi", "ronaldo", "neymar", "mbappe", "haaland", "tiger woods",
    "serena williams", "simone biles", "usain bolt", "muhammad ali",
    "espn", "sportscenter", "not top 10", "highlight", "instant replay",

    # ── Music & Artists ──────────────────────────────────────────────────────
    "concert", "headbang", "rock on", "air guitar", "drop the beat", "dj",
    "singing", "karaoke", "lip sync", "dancing", "choreography", "performance",
    "taylor swift", "shake it off", "beyonce", "formation", "rihanna", "drake",
    "kanye west", "kendrick lamar", "eminem", "post malone", "dua lipa",
    "ariana grande", "billie eilish", "bts", "blackpink", "kpop",
    "rolling stones", "beatles", "queen", "bohemian rhapsody", "freddie mercury",

    # ── Work & Office Life ───────────────────────────────────────────────────
    "monday", "friday", "weekend", "meeting", "email", "deadline", "boss",
    "coworker", "office politics", "corporate", "synergy", "pivot", "circle back",
    "touch base", "low hanging fruit", "move the needle", "bandwidth",
    "work from home", "wfh", "zoom call", "muted", "youre on mute",
    "coffee break", "lunch", "overtime", "promotion", "raise", "fired",
    "quit", "resignation", "retirement", "vacation", "pto", "sick day",

    # ── Food & Drink ─────────────────────────────────────────────────────────
    "yummy", "delicious", "tasty", "nom nom", "eating", "snacking", "foodie",
    "breakfast", "brunch", "lunch", "dinner", "dessert", "midnight snack",
    "pizza", "burger", "tacos", "sushi", "ramen", "pasta", "steak",
    "salad", "healthy", "diet", "cheat day", "treat yourself",
    "coffee", "tea", "wine", "beer", "cocktail", "shots", "cheers",
    "drunk", "hangover", "tipsy", "wasted", "sober", "water",

    # ── Relationships & Dating ───────────────────────────────────────────────
    "single", "taken", "its complicated", "crush", "friend zone",
    "first date", "dating", "tinder", "swipe right", "swipe left", "match",
    "dm slide", "shooting shot", "rizz", "flirting", "pickup line",
    "boyfriend", "girlfriend", "bae", "significant other", "partner",
    "wedding", "engaged", "married", "anniversary", "divorce", "breakup",
    "ex", "over it", "moving on", "rebound", "self love", "single life",

    # ── Gaming & Internet ────────────────────────────────────────────────────
    "gaming", "gamer", "video game", "pc gaming", "console", "controller",
    "gg", "ggs", "clutch", "noob", "pro gamer", "tryhard", "rage quit",
    "win", "lose", "victory", "defeat", "respawn", "level up", "xp",
    "fortnite", "minecraft", "call of duty", "gta", "pokemon", "zelda",
    "mario", "sonic", "among us", "impostor", "sus", "vent",
    "twitch", "streaming", "subscribe", "like and subscribe", "notification",
    "reddit", "upvote", "downvote", "comment", "repost", "viral",

    # ── Seasons & Holidays ───────────────────────────────────────────────────
    "spring", "summer", "fall", "autumn", "winter", "weather",
    "hot", "cold", "freezing", "sweating", "rain", "snow", "sunny",
    "new years", "resolution", "valentines", "cupid", "st patricks",
    "easter", "bunny", "fourth of july", "fireworks", "halloween",
    "spooky season", "costume", "trick or treat", "thanksgiving", "turkey",
    "christmas", "santa", "elf", "reindeer", "presents", "hanukkah",

    # ── Animals & Pets ───────────────────────────────────────────────────────
    "cat", "kitty", "meow", "purr", "cat loaf", "cat zoomies", "grumpy cat",
    "dog", "puppy", "woof", "bark", "fetch", "good boy", "doge", "shiba",
    "bird", "parrot", "owl", "penguin", "duck", "chicken",
    "bunny", "rabbit", "hamster", "guinea pig", "hedgehog", "ferret",
    "bear", "panda", "koala", "sloth", "otter", "seal", "whale", "dolphin",
    "monkey", "gorilla", "lion", "tiger", "elephant", "giraffe", "zebra",

    # ── Gen Z & Current Slang ────────────────────────────────────────────────
    "slay", "ate", "understood the assignment", "main character", "npc",
    "its giving", "no cap", "cap", "bet", "lowkey", "highkey", "fr fr",
    "bussin", "mid", "based", "cringe", "valid", "vibe check", "aura",
    "delulu", "situationship", "ick", "beige flag", "red flag", "green flag",
    "rent free", "living rent free", "intrusive thoughts", "call of the void",
    "roman empire", "girl dinner", "girl math", "boy math",
    "core", "aesthetic", "clean girl", "that girl", "hot girl summer",

    # ── Question Words & Simple Reactions ───────────────────────────────────
    "what", "huh", "hmm", "um", "uh", "wait", "hold on", "excuse me what",
    "say what", "come again", "pardon me", "i beg your pardon", "what now",
    "what the", "what is this", "whats happening", "whats going on",

    # ── Politicians & Public Figures ────────────────────────────────────────
    "obama", "barack obama", "michelle obama", "biden", "joe biden",
    "trump", "bernie sanders", "aoc", "nancy pelosi", "fauci",
    "elon musk", "jeff bezos", "bill gates", "mark zuckerberg", "tim cook",
    "warren buffett", "oprah winfrey", "ellen degeneres", "anderson cooper",

    # ── Additional Must-Haves ───────────────────────────────────────────────
    "i dont know", "no idea", "beats me", "who knows", "maybe",
    "perhaps", "probably", "definitely", "absolutely", "for sure",
    "of course", "obviously", "clearly", "exactly", "precisely",
    "same", "mood", "relatable", "me too", "literally me", "this is me",
]

# Supplementary queries — deep coverage for high-demand categories that the
# broad lists above only touch superficially. These fill genuine gaps.
SUPPLEMENTARY_QUERIES: List[str] = [
    # ── Cats (entire GIF subculture) ──────────────────────────────────────
    "cat reaction", "cat judging", "cat stare", "cat slap", "cat annoyed",
    "cat confused", "cat surprised", "cat angry", "cat happy", "cat love",
    "cat sleeping", "cat hiding", "cat knock off table", "cat vs cucumber",
    "cat zoom", "cat zoomies", "cat jump fail", "cat loaf", "cat stretch",
    "cat yawn", "cat sneeze", "cat blink", "cat slow blink", "cat wink",
    "cat head tilt", "cat butt wiggle", "cat pounce", "cat hunting",
    "cat box", "cat in box", "cat if fits i sits", "cat keyboard",
    "cat laptop", "cat interrupting", "cat demanding", "cat hungry",
    "cat treat", "cat nip", "catnip", "cat crazy eyes", "cat pupils",
    "cat tail flick", "cat hiss", "cat fight", "cat and dog",
    "cat bath", "cat water", "black cat", "orange cat", "tuxedo cat",
    "siamese cat", "persian cat", "maine coon", "scottish fold",
    "cat meme", "ceiling cat", "nyan cat", "pusheen", "simon cat",
    "smudge cat", "polite cat", "crying cat", "cat vibing",
    "cat dance", "cat standing", "cat walking away", "cat ignore",

    # ── Dogs (equally deep) ───────────────────────────────────────────────
    "dog reaction", "dog happy", "dog excited", "dog tail wag",
    "dog head tilt", "dog confused", "dog sad", "dog puppy eyes",
    "dog guilty", "dog caught", "dog zoomies", "dog fetch", "dog ball",
    "dog treat", "dog sit", "dog shake", "dog roll over", "dog howl",
    "dog bark", "dog whine", "dog panting", "dog smile", "dog sleeping",
    "dog snoring", "dog belly rub", "dog cuddle", "dog hug",
    "dog and cat", "dog vs cat", "golden retriever", "golden retriever gif",
    "corgi", "corgi butt", "corgi run", "husky", "husky dramatic",
    "husky tantrum", "german shepherd", "labrador", "french bulldog",
    "pomeranian", "shiba inu", "doge", "cheems", "doggo", "pupper",
    "puppy surprise", "puppy first time", "puppy clumsy",
    "dog meme", "this is fine dog", "doge meme", "much wow",
    "dog in sunglasses", "party dog", "birthday dog",

    # ── Other Animals ─────────────────────────────────────────────────────
    "raccoon", "raccoon hands", "trash panda", "red panda", "fox",
    "capybara", "capybara ok i pull up", "quokka", "axolotl",
    "frog", "frog meme", "kermit", "pepe", "frog rain",
    "duck", "duck walk", "goose", "goose chase", "untitled goose",
    "parrot party", "parrot dance", "owl", "owl turn head",
    "squirrel", "chipmunk", "hamster", "hamster eating", "hamster wheel",
    "horse", "horse laugh", "horse run", "horse meme",
    "alpaca", "llama", "llama spit",

    # ── Celebrity Reactions (iconic GIF moments) ──────────────────────────
    "robert downey jr", "rdj eye roll", "rdj sarcastic",
    "ryan reynolds", "ryan reynolds deadpool", "ryan reynolds surprised",
    "dwayne johnson", "the rock", "rock eyebrow raise", "rock clap",
    "jennifer lawrence", "j law thumbs up", "j law ok",
    "chris evans", "captain america", "that's americas ass",
    "pedro pascal", "pedro pascal crying", "pedro pascal laughing",
    "oscar isaac", "oscar isaac moon knight",
    "zendaya", "zendaya reaction", "zendaya euphoria",
    "timothee chalamet", "florence pugh",
    "will smith", "will smith slap", "fresh prince",
    "samuel l jackson", "nick fury", "samuel jackson stare",
    "leonardo dicaprio", "leo cheers", "leo toast", "leo django",
    "tom hanks", "tom hanks reaction", "forrest gump run",
    "morgan freeman", "morgan freeman pointing", "morgan freeman narrate",
    "nicolas cage", "nic cage crazy", "nic cage bees",
    "jack nicholson", "jack nicholson nod", "here comes johnny",
    "jeff goldblum", "life finds a way", "jeff goldblum laugh",
    "denzel washington", "denzel training day", "my man denzel",
    "john travolta", "travolta confused", "pulp fiction dance",
    "al pacino", "scarface", "say hello little friend",
    "jim carrey", "ace ventura", "the mask", "jim carrey alrighty then",
    "rowan atkinson", "mr bean", "mr bean reaction", "mr bean teddy",
    "steve carell", "michael scott", "thats what she said",
    "zach galifianakis", "between two ferns", "math lady meme",
    "snoop dogg", "snoop dance", "snoop drop it like its hot",
    "cardi b", "cardi b okurrr", "cardi b money",
    "lizzo", "lizzo reaction", "lizzo flute",
    "doja cat", "doja cat reaction",
    "jack black", "jack black dance", "jack black reaction",
    "chris pratt", "andy dwyer", "star lord",
    "scarlett johansson", "black widow reaction",
    "henry cavill", "henry cavill pc", "superman",
    "keanu reeves", "keanu whoa", "john wick",
    "jackie chan", "jackie chan confused", "jackie chan wtf",

    # ── Anime & Manga ─────────────────────────────────────────────────────
    "anime", "anime reaction", "anime shocked", "anime nosebleed",
    "anime blush", "anime sparkle eyes", "anime cry", "anime rage",
    "anime thumbs up", "anime laugh", "anime confused", "anime facepalm",
    "anime dance", "anime head pat", "anime wave", "anime nod",
    "anime shrug", "anime sweat drop", "anime thinking",
    "naruto", "naruto run", "sasuke", "kakashi", "itachi",
    "one piece", "luffy", "luffy laugh", "zoro lost",
    "dragon ball", "goku", "vegeta", "kamehameha", "spirit bomb",
    "attack on titan", "levi ackerman", "eren",
    "demon slayer", "tanjiro", "nezuko",
    "my hero academia", "deku", "all might",
    "jujutsu kaisen", "gojo", "gojo domain expansion",
    "spy x family", "anya", "anya heh", "anya surprised",
    "chainsaw man", "power", "makima",
    "studio ghibli", "totoro", "spirited away", "howl",
    "death note", "light yagami", "just as planned",
    "cowboy bebop", "spike spiegel", "see you space cowboy",
    "evangelion", "shinji", "get in the robot",
    "sailor moon", "sailor moon transform",
    "pokemon", "pikachu", "pikachu surprised", "charizard",
    "jojo", "jojo pose", "jotaro", "dio", "za warudo", "menacing",

    # ── Modern Memes (2023-2025) ──────────────────────────────────────────
    "skibidi", "ohio", "only in ohio", "sigma", "sigma male",
    "sigma grindset", "alpha", "giga chad", "chad yes",
    "among us", "sus", "when the impostor is sus",
    "its morbin time", "morbius", "skill issue", "cope",
    "ratio", "L", "W", "big W", "fat L", "common W",
    "npc", "npc stare", "main character energy",
    "understood the assignment", "ate and left no crumbs",
    "its giving", "not giving", "slay", "mother",
    "roman empire", "girl dinner", "girl math",
    "delulu", "delulu is the solulu", "situationship",
    "ick", "the ick", "beige flag", "red flag", "green flag",
    "no thoughts head empty", "brain smooth", "two brain cells",
    "me explaining", "me trying to", "pov", "pov me",
    "intrusive thoughts won", "call of the void",
    "live laugh love", "gaslight gatekeep girlboss",
    "he just like me fr", "literally me", "core memory",
    "rent free", "living in my head rent free",
    "emotional damage", "steven he", "failure",

    # ── Wholesome / Supportive ────────────────────────────────────────────
    "wholesome", "faith in humanity", "kindness",
    "proud of you", "you did it", "im so proud",
    "you can do it", "i believe in you", "keep going",
    "sending love", "virtual hug", "bear hug", "free hugs",
    "good vibes only", "positive energy", "manifest",
    "self care", "treat yourself", "you deserve it",
    "baby smile", "baby laugh", "baby first steps",
    "old couple", "elderly cute", "grandma reaction",
    "teacher proud", "student success",

    # ── Work / Productivity ───────────────────────────────────────────────
    "typing fast", "hacker", "coding", "programmer",
    "stackoverflow", "it works", "it compiles", "deploy friday",
    "meetings that could be emails", "corporate jargon",
    "synergy", "circle back", "ping me", "lets table that",
    "boss incoming", "alt tab", "minimize window",
    "free at last", "clock out", "friday feeling",
    "monday motivation", "hump day", "tgif",
    "remote work", "work from home", "pajama meeting",
    "multitasking", "burnout", "i need a vacation",

    # ── Reactions — Specific Gestures ─────────────────────────────────────
    "finger guns", "double finger guns", "wink finger guns",
    "chef kiss", "chefs kiss", "mwah perfect",
    "slow clap", "golf clap", "standing ovation", "round of applause",
    "curtain call", "take a bow", "bow thank you",
    "peace sign", "victory sign", "v sign",
    "middle finger", "flip off", "bird", "rude gesture",
    "fist bump", "bro fist", "fist pound",
    "pinky promise", "cross my heart",
    "finger wag", "tsk tsk", "no no no",
    "come here", "beckoning", "come hither",
    "shoo", "go away", "get out", "leave me alone",
    "talk to the hand", "hand stop", "halt",
    "jazz hands", "spirit fingers", "ta da",
]

GIPHY_SEARCH_URL = "https://api.giphy.com/v1/gifs/search"
GIPHY_TRENDING_URL = "https://api.giphy.com/v1/gifs/trending"
BATCH_SIZE = 50  # max per API call
RATE_LIMIT_DELAY = 37  # 100 calls/hr = 1 per 36s, +1s buffer
MAX_OFFSET = 4999  # Giphy pagination limit
MIN_OPTIMIZED_SIZE = 10 * 1024  # 10KB minimum to consider optimization valid

# ── Logging ─────────────────────────────────────────────────────────────────

def setup_logging(base_dir: Path) -> logging.Logger:
    """Configure dual logging: file + console."""
    log_dir = base_dir / "logs"
    log_dir.mkdir(parents=True, exist_ok=True)

    logger = logging.getLogger("pipeline")
    logger.setLevel(logging.DEBUG)

    # File handler — all messages, persistent across runs
    fh = logging.FileHandler(log_dir / "pipeline.log", encoding="utf-8")
    fh.setLevel(logging.DEBUG)
    fh.setFormatter(logging.Formatter(
        "%(asctime)s [%(levelname)s] %(message)s", datefmt="%Y-%m-%d %H:%M:%S"
    ))

    # Error-only log for quick diagnostics
    eh = logging.FileHandler(log_dir / "errors.log", encoding="utf-8")
    eh.setLevel(logging.WARNING)
    eh.setFormatter(logging.Formatter(
        "%(asctime)s [%(levelname)s] %(message)s", datefmt="%Y-%m-%d %H:%M:%S"
    ))

    # Console handler — info+
    ch = logging.StreamHandler()
    ch.setLevel(logging.INFO)
    ch.setFormatter(logging.Formatter("[%(levelname)s] %(message)s"))

    logger.addHandler(fh)
    logger.addHandler(eh)
    logger.addHandler(ch)
    return logger


# ── History Management ──────────────────────────────────────────────────────

def load_history(path: Path) -> Dict:
    """Load resumable download history."""
    if path.exists():
        try:
            with open(path) as f:
                h = json.load(f)
            # Ensure all required keys exist
            h.setdefault("downloaded_ids", [])
            h.setdefault("categories", {})
            h.setdefault("api_calls_total", 0)
            h.setdefault("total_downloaded", 0)
            h.setdefault("trending_downloaded", 0)
            h.setdefault("trending_offset", 0)
            h.setdefault("next_id", 0)
            h.setdefault("errors", {"download": 0, "convert": 0, "optimize": 0})
            h.setdefault("cleaned_raw", 0)
            return h
        except (json.JSONDecodeError, KeyError) as e:
            # Backup corrupt history, start fresh-ish
            backup = path.with_suffix(f".backup.{int(time.time())}.json")
            shutil.copy2(path, backup)
            return _default_history()
    return _default_history()


def _default_history() -> Dict:
    return {
        "downloaded_ids": [],
        "categories": {},
        "api_calls_total": 0,
        "total_downloaded": 0,
        "trending_downloaded": 0,
        "trending_offset": 0,
        "next_id": 0,
        "errors": {"download": 0, "convert": 0, "optimize": 0},
        "cleaned_raw": 0,
    }


def save_history(history: Dict, path: Path) -> None:
    """Atomic save: write to temp then rename."""
    tmp = path.with_suffix(".tmp")
    with open(tmp, "w") as f:
        json.dump(history, f, indent=2)
    tmp.rename(path)


# ── Giphy API ───────────────────────────────────────────────────────────────

def search_giphy(api_key: str, query: str, offset: int = 0) -> Optional[Dict]:
    """Search Giphy. Returns None on error."""
    try:
        resp = requests.get(GIPHY_SEARCH_URL, params={
            "api_key": api_key, "q": query, "limit": BATCH_SIZE,
            "offset": offset, "rating": "pg-13", "lang": "en",
        }, timeout=30)
        resp.raise_for_status()
        return resp.json()
    except requests.exceptions.RequestException:
        return None


def fetch_trending(api_key: str, offset: int = 0) -> Optional[Dict]:
    """Fetch trending GIFs. Returns None on error."""
    try:
        resp = requests.get(GIPHY_TRENDING_URL, params={
            "api_key": api_key, "limit": BATCH_SIZE,
            "offset": offset, "rating": "pg-13",
        }, timeout=30)
        resp.raise_for_status()
        return resp.json()
    except requests.exceptions.RequestException:
        return None


def download_gif(url: str, output_path: Path, retries: int = 2) -> bool:
    """Download a single GIF file with retry. Returns True on success."""
    for attempt in range(retries + 1):
        try:
            resp = requests.get(url, timeout=60, stream=True)
            resp.raise_for_status()
            with open(output_path, "wb") as f:
                for chunk in resp.iter_content(chunk_size=8192):
                    f.write(chunk)
            if output_path.stat().st_size > 100:
                return True
            output_path.unlink(missing_ok=True)
            return False  # File too small, don't retry
        except requests.exceptions.RequestException:
            if attempt < retries:
                time.sleep(2 * (attempt + 1))
            else:
                output_path.unlink(missing_ok=True)
                return False
    return False


def get_best_download_url(gif_data: Dict) -> Tuple[str, str]:
    """
    Select the best download URL from Giphy image variants.
    Prefers fixed_width WebP (200px, already WebP format) over original GIF.
    Returns (url, format) where format is 'webp' or 'gif'.
    """
    images = gif_data.get("images", {})

    # Prefer fixed_width WebP — already 200px wide, WebP format, much smaller
    fw = images.get("fixed_width", {})
    fw_webp = fw.get("webp", "")
    if fw_webp:
        return fw_webp, "webp"

    # Fallback: fixed_width GIF (200px, but GIF format — still saves bandwidth)
    fw_url = fw.get("url", "")
    if fw_url:
        return fw_url, "gif"

    # Last resort: original GIF (full size, can be 5-20MB)
    orig = images.get("original", {})
    orig_url = orig.get("url", "")
    return orig_url, "gif"


def save_metadata(gif_data: Dict, category: str, meta_dir: Path, file_id: str) -> None:
    """Save full Giphy metadata JSON."""
    meta = {
        "id": file_id,
        "giphy_id": gif_data["id"],
        "description": gif_data.get("title", ""),
        "source": "giphy",
        "category": category,
        "original_url": gif_data.get("url", ""),
        "source_url": gif_data.get("source", ""),
        "rating": gif_data.get("rating", ""),
        "import_datetime": gif_data.get("import_datetime", ""),
        "trending_datetime": gif_data.get("trending_datetime", ""),
        "username": gif_data.get("username", ""),
        "slug": gif_data.get("slug", ""),
        "images": {
            "original": gif_data.get("images", {}).get("original", {}),
            "fixed_width": gif_data.get("images", {}).get("fixed_width", {}),
        },
        "file_size": int(gif_data.get("images", {}).get("original", {}).get("size", 0)),
    }
    with open(meta_dir / f"{file_id}.json", "w") as f:
        json.dump(meta, f, indent=2)


# ── Pipeline Phases ─────────────────────────────────────────────────────────

def phase_download(
    api_key: str, history: Dict, history_path: Path,
    gifs_dir: Path, meta_dir: Path, seen_ids: Set[str],
    target: int, per_category: int, trending_target: int,
    api_budget: int, log: logging.Logger,
) -> Tuple[int, int]:
    """
    Download phase: fetch GIFs from Giphy within API budget.
    PRIORITY ORDER: trending → top popular → categories.
    Skips exhausted queries to avoid wasting API calls.
    Returns (new_downloads, api_calls_used).
    """
    new_downloads = 0
    api_calls = 0
    next_id = history["next_id"]

    # Only scan filesystem on first run (next_id=0) to avoid expensive glob at scale
    if next_id == 0:
        log.info("First run: scanning filesystem for max file ID...")
        for pattern in ("*.gif", "*.webp"):
            for p in gifs_dir.glob(pattern):
                if p.stem.isdigit():
                    next_id = max(next_id, int(p.stem) + 1)
        history["next_id"] = next_id

    trending_remaining = trending_target - history["trending_downloaded"]

    # ── TRENDING FIRST (priority) ──────────────────────────────────────
    # Dedicate 75% of budget to trending until target met, then give rest to categories
    if trending_remaining > 0:
        trending_budget = min(api_budget * 3 // 4, api_budget)
    else:
        trending_budget = 0
    category_budget_start = trending_budget  # categories start after trending uses its share

    trending_new = 0
    trending_offset = history["trending_offset"]
    trending_ids_db = history.setdefault("trending_ids", [])  # persistent trending ID list

    log.info(f"Trending: {history['trending_downloaded']}/{trending_target} — budget {trending_budget} calls")

    while api_calls < trending_budget and trending_new + history["trending_downloaded"] < trending_target:
        if api_calls > 0:
            time.sleep(RATE_LIMIT_DELAY)

        result = fetch_trending(api_key, trending_offset)
        api_calls += 1

        if result is None:
            log.warning(f"API error: trending offset={trending_offset}")
            history["errors"]["download"] += 1
            break

        gifs = result.get("data", [])
        if not gifs:
            log.info(f"Trending returned empty at offset {trending_offset}, wrapping to 0")
            trending_offset = 0
            break

        batch_new = 0
        for g in gifs:
            gid = g["id"]
            if gid in seen_ids:
                continue

            gif_url, dl_fmt = get_best_download_url(g)
            if not gif_url:
                continue

            file_id = f"{next_id:06d}"
            gif_path = gifs_dir / f"{file_id}.{dl_fmt}"

            if download_gif(gif_url, gif_path):
                save_metadata(g, "trending", meta_dir, file_id)
                seen_ids.add(gid)
                trending_ids_db.append(gid)
                next_id += 1
                new_downloads += 1
                trending_new += 1
                batch_new += 1
            else:
                history["errors"]["download"] += 1

        trending_offset += BATCH_SIZE
        history["trending_offset"] = trending_offset
        history["trending_downloaded"] = history.get("trending_downloaded", 0) + batch_new
        history["trending_ids"] = trending_ids_db
        history["next_id"] = next_id
        history["total_downloaded"] = len(seen_ids)

        # Save periodically (every 5 API calls) to reduce I/O at scale
        if api_calls % 5 == 0:
            history["downloaded_ids"] = list(seen_ids)
            save_history(history, history_path)

        if trending_new % 200 == 0 and trending_new > 0:
            log.info(f"  Trending: +{trending_new} this cycle ({history['trending_downloaded']} total)")

    if trending_new > 0:
        log.info(f"Trending: +{trending_new} ({history['trending_downloaded']}/{trending_target} total), {api_calls} calls")

    # ── TOP POPULAR GIFs (2000 target) ─────────────────────────────────
    # Search universally popular terms to capture the "GIFs everyone knows"
    popular_target = 2000
    popular_downloaded = history.get("popular_downloaded", 0)
    popular_remaining = popular_target - popular_downloaded
    exhausted_queries = set(history.get("exhausted_queries", []))

    if popular_remaining > 0 and api_calls < api_budget:
        # Give popular up to 10 calls per cycle (500 potential new GIFs)
        popular_budget = min(10, api_budget - api_calls)
        popular_new = 0
        popular_hist = history.setdefault("popular", {})

        log.info(f"Popular: {popular_downloaded}/{popular_target} — budget {popular_budget} calls")

        popular_calls_used = 0
        for query in TOP_POPULAR_QUERIES:
            if popular_new >= popular_remaining or popular_calls_used >= popular_budget:
                break

            exhaust_key = f"popular:{query}"
            if exhaust_key in exhausted_queries:
                continue

            offset_key = f"offset_{query}"
            offset = popular_hist.get(offset_key, 0)
            if offset >= MAX_OFFSET:
                exhausted_queries.add(exhaust_key)
                continue

            if api_calls > 0:
                time.sleep(RATE_LIMIT_DELAY)

            result = search_giphy(api_key, query, offset)
            api_calls += 1
            popular_calls_used += 1

            if result is None:
                history["errors"]["download"] += 1
                continue

            gifs = result.get("data", [])
            if not gifs:
                exhausted_queries.add(exhaust_key)
                popular_hist[offset_key] = MAX_OFFSET
                continue

            batch_new = 0
            for g in gifs:
                gid = g["id"]
                if gid in seen_ids:
                    continue
                gif_url, dl_fmt = get_best_download_url(g)
                if not gif_url:
                    continue

                file_id = f"{next_id:06d}"
                gif_path = gifs_dir / f"{file_id}.{dl_fmt}"

                if download_gif(gif_url, gif_path):
                    save_metadata(g, "popular", meta_dir, file_id)
                    seen_ids.add(gid)
                    next_id += 1
                    new_downloads += 1
                    popular_new += 1
                    batch_new += 1
                else:
                    history["errors"]["download"] += 1

            offset += BATCH_SIZE
            popular_hist[offset_key] = offset

            if batch_new == 0:
                exhausted_queries.add(exhaust_key)
                popular_hist[offset_key] = MAX_OFFSET

        history["popular"] = popular_hist
        history["popular_downloaded"] = popular_downloaded + popular_new
        history["next_id"] = next_id
        history["total_downloaded"] = len(seen_ids)
        if popular_new > 0:
            log.info(f"Popular: +{popular_new} ({history['popular_downloaded']}/{popular_target})")

    # ── VIRAL MEME GIFs (5000 target) ──────────────────────────────────
    # Specific searches for the most recognized/shared meme GIFs
    viral_target = 5000
    viral_downloaded = history.get("viral_downloaded", 0)
    viral_remaining = viral_target - viral_downloaded

    if viral_remaining > 0 and api_calls < api_budget:
        viral_budget = min(15, api_budget - api_calls)  # 15 calls per cycle
        viral_new = 0
        viral_hist = history.setdefault("viral", {})
        viral_calls_used = 0

        log.info(f"Viral memes: {viral_downloaded}/{viral_target} — budget {viral_budget} calls")

        for query in VIRAL_MEME_QUERIES:
            if viral_new >= viral_remaining or viral_calls_used >= viral_budget:
                break

            exhaust_key = f"viral:{query}"
            if exhaust_key in exhausted_queries:
                continue

            offset_key = f"offset_{query}"
            offset = viral_hist.get(offset_key, 0)
            if offset >= MAX_OFFSET:
                exhausted_queries.add(exhaust_key)
                continue

            if api_calls > 0:
                time.sleep(RATE_LIMIT_DELAY)

            result = search_giphy(api_key, query, offset)
            api_calls += 1
            viral_calls_used += 1

            if result is None:
                history["errors"]["download"] += 1
                continue

            gifs = result.get("data", [])
            if not gifs:
                exhausted_queries.add(exhaust_key)
                viral_hist[offset_key] = MAX_OFFSET
                continue

            batch_new = 0
            batch_dupes = 0
            for g in gifs:
                gid = g["id"]
                if gid in seen_ids:
                    batch_dupes += 1
                    continue
                gif_url, dl_fmt = get_best_download_url(g)
                if not gif_url:
                    continue

                file_id = f"{next_id:06d}"
                gif_path = gifs_dir / f"{file_id}.{dl_fmt}"

                if download_gif(gif_url, gif_path):
                    save_metadata(g, "viral", meta_dir, file_id)
                    seen_ids.add(gid)
                    next_id += 1
                    new_downloads += 1
                    viral_new += 1
                    batch_new += 1
                else:
                    history["errors"]["download"] += 1

            offset += BATCH_SIZE
            viral_hist[offset_key] = offset

            # If 80%+ duplicates, mark exhausted early to save API calls
            if len(gifs) > 0 and batch_dupes / len(gifs) >= 0.8:
                exhausted_queries.add(exhaust_key)
                viral_hist[offset_key] = MAX_OFFSET
                log.debug(f"Early exhaust '{query}': {batch_dupes}/{len(gifs)} dupes")

        history["viral"] = viral_hist
        history["viral_downloaded"] = viral_downloaded + viral_new
        history["next_id"] = next_id
        history["total_downloaded"] = len(seen_ids)
        if viral_new > 0:
            log.info(f"Viral: +{viral_new} ({history['viral_downloaded']}/{viral_target})")

    # ── Universal searches (10000 target) ────────────────────────────────
    universal_target = 10000
    universal_downloaded = history.get("universal_downloaded", 0)
    universal_remaining = universal_target - universal_downloaded

    if universal_remaining > 0 and api_calls < api_budget:
        universal_budget = min(20, api_budget - api_calls)  # 20 calls = ~1000 GIFs
        universal_hist = history.setdefault("universal", {})
        universal_new = 0

        log.info(f"Universal: {universal_downloaded}/{universal_target} — budget {universal_budget} calls")

        for query in TOP_500_UNIVERSAL:
            if api_calls >= api_budget or universal_new >= universal_remaining:
                break

            exhaust_key = f"universal:{query}"
            if exhaust_key in exhausted_queries:
                continue

            offset_key = f"offset_{query}"
            offset = universal_hist.get(offset_key, 0)

            if offset >= MAX_OFFSET:
                exhausted_queries.add(exhaust_key)
                continue

            while offset < MAX_OFFSET and api_calls < api_budget and universal_new < universal_remaining:
                if api_calls > 0:
                    time.sleep(RATE_LIMIT_DELAY)

                result = search_giphy(api_key, query, offset)
                api_calls += 1

                if result is None:
                    history["errors"]["download"] += 1
                    break

                gifs = result.get("data", [])
                if not gifs:
                    exhausted_queries.add(exhaust_key)
                    universal_hist[offset_key] = MAX_OFFSET
                    break

                batch_new = 0
                batch_dupes = 0
                for g in gifs:
                    gid = g["id"]
                    if gid in seen_ids:
                        batch_dupes += 1
                        continue

                    gif_url, dl_fmt = get_best_download_url(g)
                    if not gif_url:
                        continue

                    file_id = f"{next_id:06d}"
                    gif_path = gifs_dir / f"{file_id}.{dl_fmt}"

                    if download_gif(gif_url, gif_path):
                        save_metadata(g, f"universal:{query}", meta_dir, file_id)
                        seen_ids.add(gid)
                        next_id += 1
                        new_downloads += 1
                        universal_new += 1
                        batch_new += 1
                    else:
                        history["errors"]["download"] += 1

                offset += BATCH_SIZE
                universal_hist[offset_key] = offset

                # Early exhaust if 80%+ duplicates
                if len(gifs) > 0 and batch_dupes / len(gifs) >= 0.8:
                    exhausted_queries.add(exhaust_key)
                    universal_hist[offset_key] = MAX_OFFSET
                    log.debug(f"Early exhaust '{query}': {batch_dupes}/{len(gifs)} dupes")

        history["universal"] = universal_hist
        history["universal_downloaded"] = universal_downloaded + universal_new
        history["next_id"] = next_id
        history["total_downloaded"] = len(seen_ids)
        if universal_new > 0:
            log.info(f"Universal: +{universal_new} ({history['universal_downloaded']}/{universal_target})")

    # ── Supplementary searches (20000 target) ───────────────────────────
    # Deep coverage for cats, dogs, celebrities, anime, modern memes, etc.
    supplementary_target = 20000
    supplementary_downloaded = history.get("supplementary_downloaded", 0)
    supplementary_remaining = supplementary_target - supplementary_downloaded

    if supplementary_remaining > 0 and api_calls < api_budget:
        supplementary_budget = min(20, api_budget - api_calls)
        supplementary_hist = history.setdefault("supplementary", {})
        supplementary_new = 0

        log.info(f"Supplementary: {supplementary_downloaded}/{supplementary_target} — budget {supplementary_budget} calls")

        for query in SUPPLEMENTARY_QUERIES:
            if api_calls >= api_budget or supplementary_new >= supplementary_remaining:
                break

            exhaust_key = f"supplementary:{query}"
            if exhaust_key in exhausted_queries:
                continue

            offset_key = f"offset_{query}"
            offset = supplementary_hist.get(offset_key, 0)

            if offset >= MAX_OFFSET:
                exhausted_queries.add(exhaust_key)
                continue

            while offset < MAX_OFFSET and api_calls < api_budget and supplementary_new < supplementary_remaining:
                if api_calls > 0:
                    time.sleep(RATE_LIMIT_DELAY)

                result = search_giphy(api_key, query, offset)
                api_calls += 1

                if result is None:
                    history["errors"]["download"] += 1
                    break

                gifs = result.get("data", [])
                if not gifs:
                    exhausted_queries.add(exhaust_key)
                    supplementary_hist[offset_key] = MAX_OFFSET
                    break

                batch_new = 0
                batch_dupes = 0
                for g in gifs:
                    gid = g["id"]
                    if gid in seen_ids:
                        batch_dupes += 1
                        continue

                    gif_url, dl_fmt = get_best_download_url(g)
                    if not gif_url:
                        continue

                    file_id = f"{next_id:06d}"
                    gif_path = gifs_dir / f"{file_id}.{dl_fmt}"

                    if download_gif(gif_url, gif_path):
                        # Derive category from query prefix (e.g. "cat reaction" → "funny")
                        save_metadata(g, f"supplementary:{query}", meta_dir, file_id)
                        seen_ids.add(gid)
                        next_id += 1
                        new_downloads += 1
                        supplementary_new += 1
                        batch_new += 1
                    else:
                        history["errors"]["download"] += 1

                offset += BATCH_SIZE
                supplementary_hist[offset_key] = offset

                # Early exhaust if 80%+ duplicates
                if len(gifs) > 0 and batch_dupes / len(gifs) >= 0.8:
                    exhausted_queries.add(exhaust_key)
                    supplementary_hist[offset_key] = MAX_OFFSET
                    log.debug(f"Early exhaust '{query}': {batch_dupes}/{len(gifs)} dupes")

        history["supplementary"] = supplementary_hist
        history["supplementary_downloaded"] = supplementary_downloaded + supplementary_new
        history["next_id"] = next_id
        history["total_downloaded"] = len(seen_ids)
        if supplementary_new > 0:
            log.info(f"Supplementary: +{supplementary_new} ({history['supplementary_downloaded']}/{supplementary_target})")

    # ── Category downloads (remaining budget) ──────────────────────────
    category_budget = api_budget - api_calls

    for cat in CATEGORIES:
        if api_calls >= api_budget:
            break
        if history["total_downloaded"] + new_downloads >= target:
            break

        cat_name = cat["name"]
        cat_hist = history["categories"].setdefault(cat_name, {"downloaded": 0})

        if cat_hist["downloaded"] >= per_category:
            continue

        need = min(per_category - cat_hist["downloaded"], 200)  # cap per cycle
        got = 0

        for query in cat["queries"]:
            if got >= need or api_calls >= api_budget:
                break

            # Skip queries already known to be exhausted
            exhaust_key = f"{cat_name}:{query}"
            if exhaust_key in exhausted_queries:
                continue

            offset_key = f"offset_{query}"
            offset = cat_hist.get(offset_key, 0)

            # Already at max offset = exhausted
            if offset >= MAX_OFFSET:
                exhausted_queries.add(exhaust_key)
                continue

            while offset < MAX_OFFSET and got < need and api_calls < api_budget:
                if api_calls > 0:
                    time.sleep(RATE_LIMIT_DELAY)

                result = search_giphy(api_key, query, offset)
                api_calls += 1

                if result is None:
                    log.warning(f"API error: search '{query}' offset={offset}")
                    history["errors"]["download"] += 1
                    break

                gifs = result.get("data", [])
                if not gifs:
                    log.debug(f"Exhausted '{cat_name}:{query}' at offset {offset}")
                    exhausted_queries.add(exhaust_key)
                    cat_hist[offset_key] = MAX_OFFSET
                    break

                batch_new = 0
                batch_dupes = 0
                for g in gifs:
                    gid = g["id"]
                    if gid in seen_ids:
                        batch_dupes += 1
                        continue

                    gif_url, dl_fmt = get_best_download_url(g)
                    if not gif_url:
                        continue

                    file_id = f"{next_id:06d}"
                    gif_path = gifs_dir / f"{file_id}.{dl_fmt}"

                    if download_gif(gif_url, gif_path):
                        save_metadata(g, cat_name, meta_dir, file_id)
                        seen_ids.add(gid)
                        cat_hist["downloaded"] += 1
                        next_id += 1
                        new_downloads += 1
                        got += 1
                        batch_new += 1
                    else:
                        history["errors"]["download"] += 1
                        log.warning(f"Download failed: {gid}")

                offset += BATCH_SIZE
                cat_hist[offset_key] = offset
                history["next_id"] = next_id
                history["total_downloaded"] = len(seen_ids)

                # Save periodically (every 5 API calls) to reduce I/O at scale
                if api_calls % 5 == 0:
                    history["downloaded_ids"] = list(seen_ids)
                    history["exhausted_queries"] = list(exhausted_queries)
                    save_history(history, history_path)

                # Mark exhausted if no new results OR 80%+ duplicates
                if batch_new == 0 or (len(gifs) > 0 and batch_dupes / len(gifs) >= 0.8):
                    if batch_dupes / len(gifs) >= 0.8 if len(gifs) > 0 else False:
                        log.debug(f"Early exhaust '{cat_name}:{query}': {batch_dupes}/{len(gifs)} dupes")
                    exhausted_queries.add(exhaust_key)
                    cat_hist[offset_key] = MAX_OFFSET
                    history["exhausted_queries"] = list(exhausted_queries)
                    save_history(history, history_path)
                    break

        if got > 0:
            log.info(f"[{cat_name}] +{got} (total {cat_hist['downloaded']}/{per_category})")

    # Final save at end of download phase
    history["downloaded_ids"] = list(seen_ids)
    history["exhausted_queries"] = list(exhausted_queries)
    save_history(history, history_path)

    log.info(f"Download phase: +{new_downloads} new ({trending_new} trending), {api_calls} API calls, {len(exhausted_queries)} exhausted queries skipped")
    return new_downloads, api_calls


def phase_convert(
    gifs_dir: Path, processed_dir: Path, log: logging.Logger
) -> int:
    """
    Convert raw files to animated WebP for squoosh optimization.
    - GIF files: convert via process_gifs.py (ffmpeg libwebp)
    - WebP files: copy directly to full/ (already in target format from Giphy)
    Returns count processed.
    """
    script = gifs_dir.parent.parent / "process_gifs.py"
    full_dir = processed_dir / "full"
    full_dir.mkdir(parents=True, exist_ok=True)

    processed_count = 0

    # Handle raw WebP files: copy directly to full/ (already 200px WebP from Giphy)
    for webp in gifs_dir.glob("*.webp"):
        dest = full_dir / webp.name
        if not dest.exists():
            shutil.copy2(webp, dest)
            processed_count += 1

    if processed_count > 0:
        log.info(f"Convert phase: {processed_count} WebP files copied directly")

    # Handle raw GIF files: convert via ffmpeg
    unconverted = []
    for gif in gifs_dir.glob("*.gif"):
        webp = full_dir / f"{gif.stem}.webp"
        if not webp.exists():
            unconverted.append(gif.name)

    if not unconverted:
        if processed_count == 0:
            log.info("Convert phase: nothing to convert")
        return processed_count

    if not script.exists():
        log.error(f"process_gifs.py not found at {script}")
        return processed_count

    log.info(f"Convert phase: {len(unconverted)} GIFs to convert via ffmpeg")

    try:
        result = subprocess.run(
            ["python3", str(script),
             "--input", str(gifs_dir),
             "--output", str(processed_dir),
             "--profile", "balanced"],
            capture_output=True, text=True, timeout=3600,
            cwd=str(script.parent),
        )
        if result.returncode != 0:
            log.error(f"process_gifs.py failed: {result.stderr[-500:]}")
            return processed_count

        converted = sum(1 for gif in unconverted if (full_dir / f"{Path(gif).stem}.webp").exists())
        log.info(f"Convert phase: {converted} GIFs converted")
        return processed_count + converted
    except subprocess.TimeoutExpired:
        log.error("Convert phase: timed out after 1 hour")
        return processed_count
    except Exception as e:
        log.error(f"Convert phase error: {e}")
        return processed_count


def phase_optimize(
    processed_dir: Path, squoosh_script: Path, quality: int,
    jobs: int, log: logging.Logger,
) -> int:
    """Optimize WebPs with squoosh. Returns count optimized."""
    full_dir = processed_dir / "full"
    opt_dir = processed_dir / "optimized"
    opt_dir.mkdir(parents=True, exist_ok=True)

    # Count unoptimized: WebPs in full/ with no matching file in optimized/
    unoptimized = []
    for webp in full_dir.glob("*.webp"):
        opt = opt_dir / webp.name
        if not opt.exists():
            unoptimized.append(webp)

    if not unoptimized:
        log.info("Optimize phase: nothing to optimize")
        return 0

    log.info(f"Optimize phase: {len(unoptimized)} files to optimize")

    batch_script = squoosh_script.parent / "batch-squoosh.sh"
    if not batch_script.exists():
        log.error(f"batch-squoosh.sh not found at {batch_script}")
        return 0

    try:
        result = subprocess.run(
            ["bash", str(batch_script), str(full_dir), str(opt_dir), str(quality), str(jobs)],
            capture_output=True, text=True, timeout=7200,
        )
        if result.returncode != 0:
            log.error(f"batch-squoosh.sh failed: {result.stderr[-500:]}")

        # Count results
        optimized = sum(1 for w in unoptimized if (opt_dir / w.name).exists())
        log.info(f"Optimize phase: {optimized}/{len(unoptimized)} optimized")
        return optimized
    except subprocess.TimeoutExpired:
        log.error("Optimize phase: timed out after 2 hours")
        return 0
    except Exception as e:
        log.error(f"Optimize phase error: {e}")
        return 0


def phase_thumbnails(
    processed_dir: Path, jobs: int, log: logging.Logger,
) -> int:
    """Extract first-frame thumbnails from optimized animated WebPs.
    Returns count of new thumbnails generated."""
    opt_dir = processed_dir / "optimized"
    thumbs_dir = processed_dir / "thumbs"
    thumbs_dir.mkdir(parents=True, exist_ok=True)

    # Count files needing thumbnails
    need_thumbs = []
    for webp in opt_dir.glob("*.webp"):
        thumb = thumbs_dir / webp.name
        if not thumb.exists() or thumb.stat().st_size == 0:
            need_thumbs.append(webp)

    if not need_thumbs:
        log.info("Thumbnail phase: nothing to generate")
        return 0

    log.info(f"Thumbnail phase: {len(need_thumbs)} thumbnails to generate")

    thumb_script = Path(__file__).parent / "generate_thumbs.sh"
    if thumb_script.exists():
        # Use the parallel shell script for bulk generation
        try:
            result = subprocess.run(
                ["bash", str(thumb_script), str(opt_dir), str(thumbs_dir), str(jobs)],
                capture_output=True, text=True, timeout=3600,
            )
            if result.returncode != 0:
                log.error(f"generate_thumbs.sh failed: {result.stderr[-500:]}")
        except subprocess.TimeoutExpired:
            log.error("Thumbnail phase: timed out after 1 hour")
        except Exception as e:
            log.error(f"Thumbnail phase error: {e}")
    else:
        # Fallback: inline Python thumbnail extraction
        generated = 0
        for webp in need_thumbs:
            thumb = thumbs_dir / webp.name
            try:
                # Try extracting frame 1 (animated WebP)
                r = subprocess.run(
                    ["webpmux", "-get", "frame", "1", str(webp), "-o", str(thumb)],
                    capture_output=True, timeout=30,
                )
                if r.returncode != 0 or not thumb.exists() or thumb.stat().st_size == 0:
                    # Not animated or failed — copy as-is
                    shutil.copy2(webp, thumb)
                generated += 1
            except Exception:
                # Last resort: copy the file
                try:
                    shutil.copy2(webp, thumb)
                    generated += 1
                except Exception:
                    pass

        log.info(f"Thumbnail phase: {generated} thumbnails generated (inline)")
        return generated

    # Count results
    new_thumbs = sum(1 for w in need_thumbs if (thumbs_dir / w.name).exists())
    log.info(f"Thumbnail phase: {new_thumbs}/{len(need_thumbs)} generated")
    return new_thumbs


def phase_cleanup(
    gifs_dir: Path, processed_dir: Path, history: Dict,
    history_path: Path, log: logging.Logger,
) -> int:
    """
    Delete raw files (GIF or WebP) where optimized output exists and is >10KB.
    Keeps metadata JSONs untouched.
    Returns count of raw files cleaned.
    """
    opt_dir = processed_dir / "optimized"
    cleaned = 0

    # Clean both .gif and .webp raw files
    for pattern in ("*.gif", "*.webp"):
        for raw_file in gifs_dir.glob(pattern):
            opt_webp = opt_dir / f"{raw_file.stem}.webp"
            if not opt_webp.exists():
                continue
            if opt_webp.stat().st_size < MIN_OPTIMIZED_SIZE:
                log.warning(f"Skipping cleanup {raw_file.name}: optimized only {opt_webp.stat().st_size}B (<10KB)")
                continue

            raw_file.unlink()
            cleaned += 1

    if cleaned > 0:
        history["cleaned_raw"] += cleaned
        save_history(history, history_path)
        log.info(f"Cleanup phase: deleted {cleaned} raw files (optimized >10KB confirmed)")
    else:
        log.info("Cleanup phase: nothing to clean")

    return cleaned


# ── Main Loop ───────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="Continuous GIF acquisition pipeline")
    parser.add_argument("--target", type=int, default=500_000, help="Total GIF target (default 500K)")
    parser.add_argument("--trending-target", type=int, default=50_000, help="Trending GIF target (default 50K)")
    parser.add_argument("--quality", type=int, default=12, help="Squoosh optimization quality (default 12)")
    parser.add_argument("--jobs", type=int, default=4, help="Parallel optimization workers")
    parser.add_argument("--cycles", type=int, default=0, help="Max cycles (0=unlimited)")
    parser.add_argument("--test", action="store_true", help="Test mode: 50 GIFs, 1 cycle, no rate limit")
    parser.add_argument("--api-budget", type=int, default=95, help="Max API calls per hour (default 95, leave headroom)")
    args = parser.parse_args()

    # Test mode overrides
    if args.test:
        args.target = 50
        args.trending_target = 10
        args.cycles = 1
        args.api_budget = 5

    # 17 categories → per-category target
    n_categories = len(CATEGORIES)
    category_target = args.target - args.trending_target
    per_category = max(1, category_target // n_categories)

    # Paths
    base_dir = Path(__file__).parent
    load_dotenv(base_dir / ".env")
    api_key = os.getenv("GIPHY_API_KEY")
    if not api_key:
        print("ERROR: GIPHY_API_KEY not found in .env")
        sys.exit(1)

    gifs_dir = base_dir / "raw_data" / "gifs"
    meta_dir = base_dir / "raw_data" / "metadata"
    processed_dir = base_dir / "processed"
    history_path = base_dir / "download_history.json"
    squoosh_script = Path(__file__).parent.parent.parent.parent / "cleverkeys" / "squoosh" / "cli" / "squoosh-one.sh"

    gifs_dir.mkdir(parents=True, exist_ok=True)
    meta_dir.mkdir(parents=True, exist_ok=True)

    log = setup_logging(base_dir)
    history = load_history(history_path)
    seen_ids: Set[str] = set(history["downloaded_ids"])

    log.info("=" * 60)
    log.info(f"Pipeline started — target: {args.target:,} GIFs ({per_category:,}/cat + {args.trending_target:,} trending)")
    log.info(f"Current: {history['total_downloaded']:,} downloaded, {history.get('cleaned_raw', 0):,} raw cleaned")
    log.info(f"API budget: {args.api_budget}/hr, quality: q{args.quality}, workers: {args.jobs}")
    log.info(f"Squoosh: {squoosh_script}")
    log.info("=" * 60)

    cycle = 0
    while True:
        cycle += 1
        cycle_start = datetime.now()
        log.info(f"\n{'─' * 40} Cycle {cycle} {'─' * 40}")
        log.info(f"Total in collection: {len(seen_ids):,}/{args.target:,}")

        if len(seen_ids) >= args.target:
            log.info("Target reached! Running final convert/optimize/thumbs/cleanup...")
            phase_convert(gifs_dir, processed_dir, log)
            phase_optimize(processed_dir, squoosh_script, args.quality, args.jobs, log)
            phase_thumbnails(processed_dir, args.jobs, log)
            phase_cleanup(gifs_dir, processed_dir, history, history_path, log)
            log.info("Pipeline complete.")
            break

        # Phase 1: Download
        new_dl, api_used = phase_download(
            api_key, history, history_path,
            gifs_dir, meta_dir, seen_ids,
            args.target, per_category, args.trending_target,
            args.api_budget, log,
        )
        history["api_calls_total"] += api_used

        # Phase 2: Convert GIF → WebP
        phase_convert(gifs_dir, processed_dir, log)

        # Phase 3: Optimize WebP
        phase_optimize(processed_dir, squoosh_script, args.quality, args.jobs, log)

        # Phase 4: Generate thumbnails
        phase_thumbnails(processed_dir, args.jobs, log)

        # Phase 5: Cleanup raw GIFs
        phase_cleanup(gifs_dir, processed_dir, history, history_path, log)

        # Summary
        raw_count = sum(1 for p in ("*.gif", "*.webp") for _ in gifs_dir.glob(p))
        webp_count = sum(1 for _ in (processed_dir / "full").glob("*.webp")) if (processed_dir / "full").exists() else 0
        opt_count = sum(1 for _ in (processed_dir / "optimized").glob("*.webp")) if (processed_dir / "optimized").exists() else 0
        thumb_count = sum(1 for _ in (processed_dir / "thumbs").glob("*.webp")) if (processed_dir / "thumbs").exists() else 0
        errors = history["errors"]

        log.info(f"Cycle {cycle} summary:")
        log.info(f"  Downloaded: {len(seen_ids):,} total (+{new_dl} this cycle)")
        log.info(f"  Raw GIFs on disk: {raw_count:,}")
        log.info(f"  Converted WebPs: {webp_count:,}")
        log.info(f"  Optimized WebPs: {opt_count:,}")
        log.info(f"  Thumbnails: {thumb_count:,}")
        log.info(f"  Errors — DL: {errors['download']}, Convert: {errors['convert']}, Optimize: {errors['optimize']}")
        log.info(f"  Raw cleaned total: {history.get('cleaned_raw', 0):,}")

        save_history(history, history_path)

        # Check cycle limit
        if args.cycles > 0 and cycle >= args.cycles:
            log.info(f"Reached cycle limit ({args.cycles}), exiting")
            break

        # Wait for next hour
        elapsed = (datetime.now() - cycle_start).total_seconds()
        wait = max(0, 3600 - elapsed)
        if wait > 0:
            next_run = datetime.now() + timedelta(seconds=wait)
            log.info(f"Waiting {wait/60:.0f}min until next cycle ({next_run.strftime('%H:%M')})")
            time.sleep(wait)


if __name__ == "__main__":
    main()
