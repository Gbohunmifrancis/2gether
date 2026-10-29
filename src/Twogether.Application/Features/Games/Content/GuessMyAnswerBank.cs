using System.Security.Cryptography;
using Twogether.Shared.Localization;

namespace Twogether.Application.Features.Games.Content;

/// <summary>A question about one partner with four answers to choose from (Guess my answer).</summary>
public sealed record ChoiceQuestion(string Text, IReadOnlyList<string> Choices);

/// <summary>
/// Guess my answer's questions (docs/games.md). Global ones are English marked with <c>Loc.N</c> and translated where
/// they're shown; the Nigerian ones are English and Pidgin by design.
/// </summary>
public static class GuessMyAnswerBank
{
    public static IReadOnlyList<ChoiceQuestion> Global { get; } =
    [
        Q(Loc.N("Your ideal Saturday?"), Loc.N("Lie-in"), Loc.N("Long walk"), Loc.N("Friends over"), Loc.N("Market run")),
        Q(Loc.N("Your perfect breakfast?"), Loc.N("Pancakes"), Loc.N("Eggs and toast"), Loc.N("Fruit and yoghurt"), Loc.N("Just coffee")),
        Q(Loc.N("Your dream holiday?"), Loc.N("Beach"), Loc.N("Mountains"), Loc.N("Big city"), Loc.N("Road trip")),
        Q(Loc.N("Your go-to comfort food?"), Loc.N("Pizza"), Loc.N("Noodles"), Loc.N("Soup"), Loc.N("Chocolate")),
        Q(Loc.N("Your favourite season?"), Loc.N("Spring"), Loc.N("Summer"), Loc.N("Autumn"), Loc.N("Winter")),
        Q(Loc.N("How do you like to unwind?"), Loc.N("A long bath"), Loc.N("Music"), Loc.N("A good show"), Loc.N("Exercise")),
        Q(Loc.N("Your superpower of choice?"), Loc.N("Flying"), Loc.N("Invisibility"), Loc.N("Teleporting"), Loc.N("Reading minds")),
        Q(Loc.N("Morning person or night owl?"), Loc.N("Early bird"), Loc.N("Night owl"), Loc.N("Depends on the day"), Loc.N("Neither, I need coffee")),
        Q(Loc.N("Your favourite way to spend a rainy day?"), Loc.N("Film marathon"), Loc.N("Book and blanket"), Loc.N("Baking"), Loc.N("A long nap")),
        Q(Loc.N("What would you take to a desert island?"), Loc.N("My phone"), Loc.N("A book"), Loc.N("Music"), Loc.N("Snacks")),
        Q(Loc.N("Your dream pet?"), Loc.N("Dog"), Loc.N("Cat"), Loc.N("Parrot"), Loc.N("No pets, thanks")),
        Q(Loc.N("What do you order at a café?"), Loc.N("Latte"), Loc.N("Tea"), Loc.N("Hot chocolate"), Loc.N("Smoothie")),
        Q(Loc.N("Your karaoke style?"), Loc.N("Power ballad"), Loc.N("Rap"), Loc.N("A duet"), Loc.N("I'll just watch")),
        Q(Loc.N("Your favourite kind of film?"), Loc.N("Comedy"), Loc.N("Thriller"), Loc.N("Romance"), Loc.N("Action")),
        Q(Loc.N("What do you do first on a day off?"), Loc.N("Sleep in"), Loc.N("Tidy up"), Loc.N("Head out"), Loc.N("Scroll my phone")),
        Q(Loc.N("Your ideal date?"), Loc.N("Dinner out"), Loc.N("Cooking at home"), Loc.N("Dancing"), Loc.N("A picnic")),
        Q(Loc.N("How do you handle a long queue?"), Loc.N("Wait patiently"), Loc.N("Chat to people"), Loc.N("Leave"), Loc.N("Sigh loudly")),
        Q(Loc.N("Your favourite dessert?"), Loc.N("Ice cream"), Loc.N("Cake"), Loc.N("Fruit salad"), Loc.N("Cheesecake")),
        Q(Loc.N("What makes you feel most loved?"), Loc.N("Kind words"), Loc.N("Time together"), Loc.N("Little gifts"), Loc.N("A big hug")),
        Q(Loc.N("The chore you dislike most?"), Loc.N("Washing up"), Loc.N("Laundry"), Loc.N("Cleaning the bathroom"), Loc.N("Taking out the bins")),
        Q(Loc.N("Your dream home?"), Loc.N("City flat"), Loc.N("Countryside cottage"), Loc.N("Beach house"), Loc.N("Big family home")),
        Q(Loc.N("Your favourite snack?"), Loc.N("Crisps"), Loc.N("Popcorn"), Loc.N("Nuts"), Loc.N("Sweets")),
        Q(Loc.N("What kind of gift do you love most?"), Loc.N("A surprise"), Loc.N("Something I asked for"), Loc.N("Something handmade"), Loc.N("An experience")),
        Q(Loc.N("Your perfect birthday?"), Loc.N("Big party"), Loc.N("Quiet dinner"), Loc.N("A trip away"), Loc.N("A day of doing nothing")),
        Q(Loc.N("What would you splurge on?"), Loc.N("Clothes"), Loc.N("Gadgets"), Loc.N("Travel"), Loc.N("Good food")),
        Q(Loc.N("Your road trip music?"), Loc.N("Pop"), Loc.N("Hip-hop"), Loc.N("Rock"), Loc.N("Old classics")),
        Q(Loc.N("The best part of a holiday?"), Loc.N("The food"), Loc.N("The sights"), Loc.N("Doing nothing"), Loc.N("The shopping")),
        Q(Loc.N("How do you say sorry?"), Loc.N("With words"), Loc.N("With a hug"), Loc.N("With a treat"), Loc.N("By fixing it")),
        Q(Loc.N("Your workout of choice?"), Loc.N("Running"), Loc.N("The gym"), Loc.N("Dancing"), Loc.N("Yoga")),
        Q(Loc.N("Your dream job, just for fun?"), Loc.N("Chef"), Loc.N("Pilot"), Loc.N("Musician"), Loc.N("Explorer")),
        Q(Loc.N("What do you notice first about someone?"), Loc.N("Their smile"), Loc.N("Their eyes"), Loc.N("Their voice"), Loc.N("Their style")),
        Q(Loc.N("Your ideal night in?"), Loc.N("Board games"), Loc.N("Film and snacks"), Loc.N("Cooking together"), Loc.N("An early night")),
        Q(Loc.N("Your favourite drink?"), Loc.N("Water"), Loc.N("Juice"), Loc.N("A fizzy drink"), Loc.N("Tea")),
        Q(Loc.N("How do you pack for a trip?"), Loc.N("Weeks ahead"), Loc.N("The night before"), Loc.N("Last minute"), Loc.N("Someone else does it")),
        Q(Loc.N("Your ideal weather?"), Loc.N("Hot and sunny"), Loc.N("A cool breeze"), Loc.N("Rainy"), Loc.N("Snowy")),
        Q(Loc.N("What would you most like to learn?"), Loc.N("A language"), Loc.N("An instrument"), Loc.N("To cook"), Loc.N("To dance")),
        Q(Loc.N("What do you use your phone for most?"), Loc.N("Messages"), Loc.N("Photos"), Loc.N("Music"), Loc.N("Social media")),
        Q(Loc.N("Your favourite time of day?"), Loc.N("Early morning"), Loc.N("Afternoon"), Loc.N("Evening"), Loc.N("Late night")),
        Q(Loc.N("How do you take your eggs?"), Loc.N("Scrambled"), Loc.N("Fried"), Loc.N("Boiled"), Loc.N("No eggs, please")),
        Q(Loc.N("Your biggest guilty pleasure?"), Loc.N("Reality TV"), Loc.N("Junk food"), Loc.N("Online shopping"), Loc.N("Sleeping in")),
        Q(Loc.N("Your idea of a perfect gift?"), Loc.N("Flowers"), Loc.N("Jewellery"), Loc.N("A handwritten letter"), Loc.N("Tickets to something")),
        Q(Loc.N("What could you not live without?"), Loc.N("My phone"), Loc.N("Music"), Loc.N("Coffee"), Loc.N("My friends")),
        Q(Loc.N("Your dream car?"), Loc.N("Something sporty"), Loc.N("Something big"), Loc.N("Something electric"), Loc.N("I don't need one")),
        Q(Loc.N("Your happy place?"), Loc.N("The beach"), Loc.N("The kitchen"), Loc.N("Bed"), Loc.N("Out with friends")),
        Q(Loc.N("How do you celebrate good news?"), Loc.N("Call someone"), Loc.N("Treat myself"), Loc.N("Dance"), Loc.N("Keep it quiet")),
    ];

    public static IReadOnlyList<ChoiceQuestion> Nigerian { get; } =
    [
        Q("Your owambe go-to?", "Jollof", "Small chops", "Asun", "Moi moi"),
        Q("Your ideal Lagos weekend?", "Beach day", "Owambe", "Lekki brunch", "Indoors, no traffic"),
        Q("Your ideal Abuja weekend?", "Jabi Lake walk", "Suya at night", "Millennium Park picnic", "Sleeping in"),
        Q("Your Nollywood favourite?", "Romance", "Comedy", "Family drama", "Epic"),
        Q("Your Afrobeats pick?", "Burna Boy", "Wizkid", "Davido", "Tems"),
        Q("Your swallow of choice?", "Pounded yam", "Eba", "Amala", "Semo"),
        Q("Your soup of choice?", "Egusi", "Efo riro", "Ogbono", "Banga"),
        Q("Super Eagles match day, you're?", "Shouting at the TV", "Watching quietly", "At the viewing centre", "Just checking the score"),
        Q("Your Friday night?", "Owambe", "Suya spot", "Netflix and chill", "Night out with friends"),
        Q("How do you beat Lagos traffic?", "Leave at 5am", "Hop on an okada", "Music and patience", "Stay at home"),
        Q("Your favourite street food?", "Suya", "Boli and fish", "Puff-puff", "Akara"),
        Q("Your owambe outfit?", "Agbada", "Lace and gele", "Ankara", "Senator"),
        Q("Your ideal Sunday lunch?", "Rice and stew", "Ofada rice", "Pounded yam and egusi", "Fried rice and chicken"),
        Q("Your favourite Naija drink?", "Zobo", "Chapman", "Malt", "Palm wine"),
        Q("Your dream Naija getaway?", "Obudu Ranch", "Erin Ijesha waterfall", "Calabar carnival", "Lagos beach house"),
        Q("When NEPA takes light, you?", "Start the gen", "Go to sleep", "Sit outside", "Charge at a friend's place"),
        Q("Your favourite Naija snack?", "Chin chin", "Plantain chips", "Kuli kuli", "Gala"),
        Q("Your breakfast of champions?", "Akara and pap", "Bread and tea", "Yam and egg", "Moi moi"),
        Q("Your Detty December plan?", "Every concert", "Village visit", "Beach parties", "Rest at home"),
        Q("Your favourite way to eat plantain?", "Dodo", "Boli", "Plantain porridge", "Plantain chips"),
        Q("Your shopping spot?", "Balogun market", "The mall", "Instagram vendors", "Wuse market"),
        Q("Your rice of choice?", "Party jollof", "Fried rice", "Ofada", "Coconut rice"),
        Q("Your kind of Naija comedy?", "Skits online", "Stand-up show", "Nollywood comedy", "Family sitcom"),
        Q("Your football club?", "Chelsea", "Arsenal", "Man United", "I don't do football"),
        Q("How do you greet your in-laws?", "Full prostrate", "Kneel down", "Warm hug", "Big smile and a bow"),
        Q("Your wedding must-have?", "Live band", "Big jollof pot", "Aso ebi", "Dance entrance"),
        Q("Your favourite pepper soup?", "Goat meat", "Catfish", "Chicken", "Assorted"),
        Q("First thing you buy at the market?", "Tomatoes and pepper", "Fresh fish", "Fruits", "Snacks for the road"),
        Q("Your road trip snack?", "Gala", "Groundnuts", "Corn and ube", "Meat pie"),
        Q("Your phone is full of?", "Afrobeats", "Skits", "Family WhatsApp", "Owambe pictures"),
        Q("Your dream Naija city to live in?", "Lagos", "Abuja", "Port Harcourt", "Enugu"),
        Q("Your role at a party?", "On the dance floor", "At the food table", "Spraying money", "Taking pictures"),
        Q("Your favourite meat?", "Suya", "Asun", "Kilishi", "Peppered chicken"),
        Q("Your go-to Naija phrase?", "No wahala", "Wetin dey?", "E go better", "Omo!"),
    ];

    public static IReadOnlyList<ChoiceQuestion> For(string region) => region == GameRegion.Nigeria ? Nigerian : Global;

    /// <summary><paramref name="count"/> different questions at random, for one game.</summary>
    public static IReadOnlyList<ChoiceQuestion> Pick(string region, int count)
    {
        var copy = For(region).ToArray();
        RandomNumberGenerator.Shuffle(copy.AsSpan());
        return copy.Take(count).ToList();
    }

    private static ChoiceQuestion Q(string text, params string[] choices) => new(text, choices);
}
