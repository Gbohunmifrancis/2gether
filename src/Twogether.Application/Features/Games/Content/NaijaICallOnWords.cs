namespace Twogether.Application.Features.Games;

/// <summary>
/// I call on's offline words for Nigerian couples, added to the global lists in <see cref="GameEngine"/>. Lower case,
/// as answers are compared trimmed and lower-cased. A word missing here isn't refused: the partner judges it.
/// </summary>
public static class NaijaICallOnWords
{
    /// <summary>Names from across Nigeria: Yoruba, Igbo, Hausa, Edo, Ijaw, Efik, Ibibio, Tiv, Urhobo and more.</summary>
    public static readonly IReadOnlyList<string> Names =
    [
        "abubakar", "adaeze", "adaobi", "adebayo", "adewale", "aisha", "amaka", "aminu", "ayo", "ayodele", "ayomide",
        "babatunde", "bisi", "binta", "bola", "bolanle", "bukola",
        "chiamaka", "chidi", "chidinma", "chinedu", "chioma", "chisom", "chukwuemeka", "chinonso",
        "damilola", "dayo", "dupe", "ebere", "ebi", "ebiere", "efe", "eghosa", "ekaette", "ekanem", "ekene", "emeka", "eniola", "eno", "etim",
        "fatima", "femi", "folake", "funke", "funmilayo", "gbenga", "garba",
        "hadiza", "halima", "hauwa", "ibrahim", "ifeanyi", "ifeoma", "ikenna", "inyang", "iyabo",
        "jamila", "jide", "jumoke", "kabiru", "kayode", "kelechi", "kemi", "kunle",
        "ladi", "lawal", "lola", "maryam", "musa", "ngozi", "nkechi", "nneka", "nosa",
        "obinna", "oghenekaro", "ogechi", "olu", "oluwaseun", "osagie", "osaro", "osas",
        "rabi", "sade", "sani", "segun", "seun", "sola", "tamuno", "tari", "temitope", "terver", "timi", "tobi", "tunde",
        "uche", "uchenna", "umar", "usman", "uyi", "yakubu", "yemi", "yetunde", "yusuf", "zainab", "zubairu"
    ];

    /// <summary>All 36 states and the FCT, every state capital, and major towns and districts.</summary>
    public static readonly IReadOnlyList<string> Places =
    [
        // States and the FCT
        "abia", "adamawa", "akwa ibom", "anambra", "bauchi", "bayelsa", "benue", "borno", "cross river", "delta", "ebonyi", "edo",
        "ekiti", "enugu", "gombe", "imo", "jigawa", "kaduna", "kano", "katsina", "kebbi", "kogi", "kwara", "lagos", "nasarawa",
        "niger", "ogun", "ondo", "osun", "oyo", "plateau", "rivers", "sokoto", "taraba", "yobe", "zamfara", "abuja", "fct",
        // State capitals
        "umuahia", "yola", "uyo", "awka", "yenagoa", "makurdi", "maiduguri", "calabar", "asaba", "abakaliki", "benin", "benin city",
        "ado-ekiti", "ado ekiti", "owerri", "dutse", "birnin kebbi", "lokoja", "ilorin", "ikeja", "lafia", "minna", "abeokuta",
        "akure", "osogbo", "oshogbo", "ibadan", "jos", "port harcourt", "jalingo", "damaturu", "gusau",
        // Towns and districts
        "aba", "onitsha", "nnewi", "warri", "sapele", "ughelli", "ogbomoso", "ilesa", "ile-ife", "ife", "ede", "iwo", "zaria",
        "kafanchan", "nsukka", "okene", "ijebu ode", "sagamu", "ota", "badagry", "epe", "ikorodu", "lekki", "ikoyi", "yaba",
        "surulere", "victoria island", "iseyin", "owo", "ikare", "bida", "suleja", "keffi", "kontagora", "gboko", "otukpo",
        "potiskum", "nguru", "azare", "funtua", "daura", "gashua", "hadejia", "bonny", "brass", "ogoja", "ikom", "oron", "eket",
        "ikot ekpene", "afikpo", "okigwe", "orlu", "auchi", "ekpoma", "mubi", "biu", "wukari", "argungu", "offa", "jebba",
        "obudu", "idanre", "kumo", "garki", "wuse", "maitama", "gwagwalada", "agbor", "okitipupa", "ubiaja", "umunede", "ilaro"
    ];

    /// <summary>Animals: the familiar ones everywhere, plus West African wildlife and farm animals.</summary>
    public static readonly IReadOnlyList<string> Animals =
    [
        "aardvark", "antelope", "ant", "baboon", "bat", "buffalo", "bushbuck", "camel", "cat", "chameleon", "cheetah", "chicken",
        "chimpanzee", "cow", "crocodile", "dog", "donkey", "duck", "duiker", "eagle", "egret", "elephant", "fox", "frog", "giraffe",
        "goat", "gorilla", "grasscutter", "guinea fowl", "hare", "hawk", "hedgehog", "hen", "hippo", "hippopotamus", "horse",
        "hyena", "ibis", "iguana", "impala", "jackal", "jaguar", "kangaroo", "kingfisher", "koala", "kob", "kite", "leopard",
        "lion", "lizard", "manatee", "monkey", "mongoose", "mosquito", "newt", "nightingale", "ostrich", "otter", "owl", "ox",
        "pangolin", "parrot", "pig", "pigeon", "porcupine", "python", "quail", "rabbit", "ram", "rat", "rhino", "rooster",
        "scorpion", "sheep", "snail", "snake", "squirrel", "termite", "tiger", "toad", "tortoise", "turkey", "urial",
        "vulture", "viper", "warthog", "weaver bird", "whale", "wolf", "xerus", "yak", "zebra", "zebu"
    ];

    /// <summary>Nigerian dishes, soups, snacks, swallows and drinks.</summary>
    public static readonly IReadOnlyList<string> Foods =
    [
        "abacha", "adalu", "agidi", "akara", "akamu", "amala", "asun", "ayamase", "banga", "boli", "bitterleaf soup", "chin chin",
        "danwake", "dodo", "dundun", "eba", "edikang ikong", "efo riro", "egusi", "ewa agoyin", "ewedu", "fufu", "fura",
        "fura da nono", "garri", "gari", "gizdodo", "groundnut", "ikokore", "isi ewu", "jollof", "jollof rice", "kilishi",
        "kosai", "kuli kuli", "kunu", "masa", "miyan kuka", "moi moi", "moimoi", "nkwobi", "ofada", "ofada rice", "ofe onugbu",
        "ogbono", "ogi", "oha soup", "okpa", "pap", "pepper soup", "plantain", "pounded yam", "puff puff", "puff-puff", "semo",
        "semovita", "suya", "tuwo", "tuwo shinkafa", "ugba", "ukwa", "unripe plantain", "vegetable soup", "waina", "wara",
        "yam porridge", "yam pottage", "zobo"
    ];
}
