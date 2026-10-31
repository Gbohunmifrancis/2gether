using Twogether.Shared.Localization;

namespace Twogether.Application.Features.Games;

/// <summary>
/// General knowledge for when Open Trivia DB can't be reached. Written in English with <c>Loc.N</c> and shown with
/// <c>Loc.T</c>, so each partner reads it in their own language. Only settled facts: nothing disputed or dated.
/// </summary>
public static class GlobalTrivia
{
    public static IReadOnlyList<TriviaQuestion> Draw(int count, IReadOnlySet<string>? seen = null) => TriviaItem.Draw(Questions, count, translate: true, seen);

    public static readonly IReadOnlyList<TriviaItem> Questions =
    [
        // Science and nature
        new(Loc.N("What is the largest planet in our solar system?"), Loc.N("Jupiter"), Loc.N("Saturn"), Loc.N("Neptune"), Loc.N("Earth")),
        new(Loc.N("Which planet is closest to the Sun?"), Loc.N("Mercury"), Loc.N("Venus"), Loc.N("Mars"), Loc.N("Earth")),
        new(Loc.N("Which planet is known as the Red Planet?"), Loc.N("Mars"), Loc.N("Jupiter"), Loc.N("Venus"), Loc.N("Mercury")),
        new(Loc.N("Which planet is famous for its bright rings?"), Loc.N("Saturn"), Loc.N("Mars"), Loc.N("Mercury"), Loc.N("Venus")),
        new(Loc.N("What is the chemical symbol for gold?"), "Au", "Ag", "Gd", "Go"),
        new(Loc.N("Which gas do plants take in from the air to make their food?"), Loc.N("Carbon dioxide"), Loc.N("Oxygen"), Loc.N("Nitrogen"), Loc.N("Helium")),
        new(Loc.N("What is the hardest natural substance?"), Loc.N("Diamond"), Loc.N("Iron"), Loc.N("Granite"), Loc.N("Quartz")),
        new(Loc.N("At what temperature in degrees Celsius does water freeze?"), "0", "10", "32", "100"),
        new(Loc.N("At sea level, at what temperature in degrees Celsius does water boil?"), "100", "90", "120", "212"),
        new(Loc.N("How many bones are in an adult human body?"), "206", "180", "250", "302"),
        new(Loc.N("Which organ pumps blood around the body?"), Loc.N("The heart"), Loc.N("The liver"), Loc.N("The lungs"), Loc.N("The kidneys")),
        new(Loc.N("What is the fastest land animal?"), Loc.N("Cheetah"), Loc.N("Lion"), Loc.N("Horse"), Loc.N("Greyhound")),
        new(Loc.N("What is the largest animal that has ever lived?"), Loc.N("Blue whale"), Loc.N("African elephant"), Loc.N("Giraffe"), Loc.N("Great white shark")),
        new(Loc.N("How many legs does a spider have?"), "8", "6", "10", "12"),
        new(Loc.N("What do bees make from the nectar of flowers?"), Loc.N("Honey"), Loc.N("Jam"), Loc.N("Silk"), Loc.N("Milk")),
        new(Loc.N("What is H2O better known as?"), Loc.N("Water"), Loc.N("Salt"), Loc.N("Hydrogen gas"), Loc.N("Sugar")),
        new(Loc.N("Who described the law of universal gravitation in 1687?"), Loc.N("Isaac Newton"), Loc.N("Albert Einstein"), Loc.N("Charles Darwin"), Loc.N("Nikola Tesla")),
        new(Loc.N("Who came up with the theory of relativity?"), Loc.N("Albert Einstein"), Loc.N("Isaac Newton"), Loc.N("Galileo Galilei"), Loc.N("Marie Curie")),
        new(Loc.N("How many hearts does an octopus have?"), "3", "1", "2", "8"),
        new(Loc.N("What is the largest organ of the human body?"), Loc.N("The skin"), Loc.N("The liver"), Loc.N("The brain"), Loc.N("The heart")),
        new(Loc.N("What do caterpillars turn into?"), Loc.N("Butterflies"), Loc.N("Bees"), Loc.N("Beetles"), Loc.N("Dragonflies")),
        new(Loc.N("What is the closest star to Earth?"), Loc.N("The Sun"), Loc.N("Sirius"), Loc.N("The North Star"), Loc.N("Alpha Centauri")),
        new(Loc.N("How many teeth does an adult usually have?"), "32", "28", "30", "36"),
        new(Loc.N("What colour do you get when you mix blue and yellow?"), Loc.N("Green"), Loc.N("Purple"), Loc.N("Orange"), Loc.N("Brown")),
        new(Loc.N("Which fruit is dried to make raisins?"), Loc.N("Grapes"), Loc.N("Plums"), Loc.N("Figs"), Loc.N("Cherries")),
        new(Loc.N("What is the main ingredient in guacamole?"), Loc.N("Avocado"), Loc.N("Tomato"), Loc.N("Cucumber"), Loc.N("Peas")),

        // Geography
        new(Loc.N("How many continents are there?"), "7", "5", "6", "8"),
        new(Loc.N("What is the largest ocean on Earth?"), Loc.N("The Pacific Ocean"), Loc.N("The Atlantic Ocean"), Loc.N("The Indian Ocean"), Loc.N("The Arctic Ocean")),
        new(Loc.N("What is the tallest mountain above sea level?"), Loc.N("Mount Everest"), Loc.N("K2"), Loc.N("Kilimanjaro"), Loc.N("Mont Blanc")),
        new(Loc.N("What is the largest hot desert in the world?"), Loc.N("The Sahara"), Loc.N("The Gobi"), Loc.N("The Kalahari"), Loc.N("The Mojave")),
        new(Loc.N("What is the capital of Japan?"), Loc.N("Tokyo"), Loc.N("Kyoto"), Loc.N("Osaka"), Loc.N("Seoul")),
        new(Loc.N("What is the capital of Australia?"), Loc.N("Canberra"), Loc.N("Sydney"), Loc.N("Melbourne"), Loc.N("Perth")),
        new(Loc.N("What is the capital of Canada?"), Loc.N("Ottawa"), Loc.N("Toronto"), Loc.N("Vancouver"), Loc.N("Montreal")),
        new(Loc.N("What is the capital of Kenya?"), Loc.N("Nairobi"), Loc.N("Mombasa"), Loc.N("Kampala"), Loc.N("Addis Ababa")),
        new(Loc.N("What is the capital of Egypt?"), Loc.N("Cairo"), Loc.N("Alexandria"), Loc.N("Tunis"), Loc.N("Khartoum")),
        new(Loc.N("Which is the largest country in the world by area?"), Loc.N("Russia"), Loc.N("Canada"), Loc.N("China"), Loc.N("Brazil")),
        new(Loc.N("In which country is the Taj Mahal?"), Loc.N("India"), Loc.N("Pakistan"), Loc.N("Iran"), Loc.N("Egypt")),
        new(Loc.N("In which country is Machu Picchu?"), Loc.N("Peru"), Loc.N("Mexico"), Loc.N("Chile"), Loc.N("Colombia")),
        new(Loc.N("Which language is most widely spoken in Brazil?"), Loc.N("Portuguese"), Loc.N("Spanish"), Loc.N("English"), Loc.N("French")),
        new(Loc.N("Which country gave the Statue of Liberty to the United States?"), Loc.N("France"), Loc.N("The United Kingdom"), Loc.N("Spain"), Loc.N("Italy")),
        new(Loc.N("What is the currency of Japan?"), Loc.N("The yen"), Loc.N("The yuan"), Loc.N("The won"), Loc.N("The rupee")),
        new(Loc.N("What is the capital of Italy?"), Loc.N("Rome"), Loc.N("Milan"), Loc.N("Venice"), Loc.N("Naples")),
        new(Loc.N("What is the capital of Brazil?"), Loc.N("Brasília"), Loc.N("Rio de Janeiro"), Loc.N("São Paulo"), Loc.N("Salvador")),
        new(Loc.N("What is the capital of Spain?"), Loc.N("Madrid"), Loc.N("Barcelona"), Loc.N("Seville"), Loc.N("Valencia")),
        new(Loc.N("In which city is the Eiffel Tower?"), Loc.N("Paris"), Loc.N("London"), Loc.N("Rome"), Loc.N("Brussels")),
        new(Loc.N("In which country is Mount Kilimanjaro?"), Loc.N("Tanzania"), Loc.N("Kenya"), Loc.N("Uganda"), Loc.N("Ethiopia")),
        new(Loc.N("Which ocean lies between Africa and Australia?"), Loc.N("The Indian Ocean"), Loc.N("The Atlantic Ocean"), Loc.N("The Pacific Ocean"), Loc.N("The Arctic Ocean")),
        new(Loc.N("Which country has a maple leaf on its flag?"), Loc.N("Canada"), Loc.N("Sweden"), Loc.N("Switzerland"), Loc.N("Norway")),
        new(Loc.N("On which continent is Egypt?"), Loc.N("Africa"), Loc.N("Asia"), Loc.N("Europe"), Loc.N("South America")),

        // Arts and history
        new(Loc.N("Who painted the Mona Lisa?"), Loc.N("Leonardo da Vinci"), Loc.N("Michelangelo"), Loc.N("Raphael"), Loc.N("Vincent van Gogh")),
        new(Loc.N("Who painted The Starry Night?"), Loc.N("Vincent van Gogh"), Loc.N("Claude Monet"), Loc.N("Pablo Picasso"), Loc.N("Salvador Dalí")),
        new(Loc.N("Who wrote Romeo and Juliet?"), Loc.N("William Shakespeare"), Loc.N("Charles Dickens"), Loc.N("Jane Austen"), Loc.N("Mark Twain")),
        new(Loc.N("Who wrote the Harry Potter books?"), Loc.N("J. K. Rowling"), Loc.N("Roald Dahl"), Loc.N("C. S. Lewis"), Loc.N("J. R. R. Tolkien")),
        new(Loc.N("Who composed Für Elise?"), Loc.N("Ludwig van Beethoven"), Loc.N("Wolfgang Amadeus Mozart"), Loc.N("Johann Sebastian Bach"), Loc.N("Frédéric Chopin")),
        new(Loc.N("Who was the first person to walk on the Moon?"), Loc.N("Neil Armstrong"), Loc.N("Buzz Aldrin"), Loc.N("Yuri Gagarin"), Loc.N("Michael Collins")),
        new(Loc.N("Who was the first person to travel into space?"), Loc.N("Yuri Gagarin"), Loc.N("Neil Armstrong"), Loc.N("Alan Shepard"), Loc.N("John Glenn")),
        new(Loc.N("In which country is the Great Wall?"), Loc.N("China"), Loc.N("Japan"), Loc.N("Mongolia"), Loc.N("India")),
        new(Loc.N("How many keys does a standard piano have?"), "88", "76", "61", "100"),
        new(Loc.N("Who wrote Things Fall Apart?"), Loc.N("Chinua Achebe"), Loc.N("Wole Soyinka"), Loc.N("Ngũgĩ wa Thiong'o"), Loc.N("Chimamanda Ngozi Adichie")),
        new(Loc.N("Who wrote Don Quixote?"), Loc.N("Miguel de Cervantes"), Loc.N("Gabriel García Márquez"), Loc.N("Pablo Neruda"), Loc.N("Federico García Lorca")),
        new(Loc.N("Who painted the ceiling of the Sistine Chapel?"), Loc.N("Michelangelo"), Loc.N("Leonardo da Vinci"), Loc.N("Raphael"), Loc.N("Donatello")),
        new(Loc.N("In which country did the ancient Olympic Games begin?"), Loc.N("Greece"), Loc.N("Italy"), Loc.N("Egypt"), Loc.N("France")),
        new(Loc.N("How many strings does a standard guitar have?"), "6", "4", "5", "8"),

        // Numbers, sport and everyday life
        new(Loc.N("How many days are in a leap year?"), "366", "365", "364", "360"),
        new(Loc.N("Which month has the fewest days?"), Loc.N("February"), Loc.N("April"), Loc.N("June"), Loc.N("November")),
        new(Loc.N("What is 12 times 12?"), "144", "124", "132", "156"),
        new(Loc.N("What is the smallest prime number?"), "2", "1", "3", "0"),
        new(Loc.N("How many sides does a hexagon have?"), "6", "5", "7", "8"),
        new(Loc.N("How many rings are on the Olympic flag?"), "5", "4", "6", "7"),
        new(Loc.N("How many players does each football team have on the pitch?"), "11", "9", "10", "12"),
        new(Loc.N("How long is a standard football match, not counting extra time?"), Loc.N("90 minutes"), Loc.N("60 minutes"), Loc.N("80 minutes"), Loc.N("100 minutes")),
        new(Loc.N("Which bird is a symbol of peace?"), Loc.N("The dove"), Loc.N("The eagle"), Loc.N("The crow"), Loc.N("The parrot")),
        new(Loc.N("How many colours are in a rainbow?"), "7", "5", "6", "8"),
        new(Loc.N("How many hours are in a week?"), "168", "144", "156", "172"),
        new(Loc.N("How many weeks are in a year?"), "52", "48", "50", "56"),
        new(Loc.N("How many players does a basketball team have on court?"), "5", "6", "7", "4"),
        new(Loc.N("Which sport is played at Wimbledon?"), Loc.N("Tennis"), Loc.N("Golf"), Loc.N("Cricket"), Loc.N("Football")),
        new(Loc.N("Which shape has four equal sides and four right angles?"), Loc.N("A square"), Loc.N("A rectangle"), Loc.N("A rhombus"), Loc.N("A triangle")),
    ];
}
