using Twogether.Application.Features.Ai;
using Twogether.Core.Enums;
using Twogether.Shared.Results;

namespace Twogether.UnitTests.Support;

/// <summary>Claude in memory: records every request and answers what the test sets. Nothing ever leaves the machine.</summary>
public sealed class FakeClaude : IClaudeClient
{
    private readonly List<ClaudeRequest> requests = [];

    public bool IsConfigured { get; set; } = true;
    public string ModelName => "test-model";
    public Func<ClaudeRequest, Result<ClaudeReply>> Answer { get; set; } = Default;

    public IReadOnlyList<ClaudeRequest> Requests
    {
        get { lock (requests) return requests.ToList(); }
    }

    public int Calls(AiFeature feature) => Requests.Count(item => item.Feature == feature);

    public Task<Result<ClaudeReply>> CompleteJsonAsync(ClaudeRequest request, CancellationToken cancellationToken)
    {
        lock (requests) requests.Add(request);
        return Task.FromResult(Answer(request));
    }

    public static Result<ClaudeReply> Reply(string json) => new ClaudeReply(json, 900, 120);

    public static Result<ClaudeReply> Default(ClaudeRequest request) => request.Feature switch
    {
        AiFeature.DareIdeas => Reply("""
            {"ideas":[
              {"exercise_type":"squat","target":20,"time":"18:30","reason":"Five more than last week, you've got this."},
              {"exercise_type":"plank","target":45,"time":"07:15","reason":"A calm hold before breakfast."},
              {"exercise_type":"pushUp","target":12,"time":"19:00","reason":"Push-ups, a notch up from Tuesday."}]}
            """),
        AiFeature.Coach => Reply("""{"note":"Twenty squats at a steady pace, and not one rep too fast. Day four of your streak! Next time, pause for a breath at the bottom."}"""),
        AiFeature.WeeklyRecap => Reply("""
            {"title":"A week of full hearts","lines":["Five hearts between you two.","Three games, and you won two.","Four photos in the album."],
             "suggestion":"Try a plank dare together on Wednesday."}
            """),
        AiFeature.Plan => Reply("""
            {"dates":[{"title":"Blanket fort film night","why":"Cosy, free, and nobody has to get dressed."},
                      {"title":"Cook a new recipe together","why":"A small adventure in your own kitchen."},
                      {"title":"Candlelit board games","why":"Romantic and costs nothing."}],
             "games":[{"game_type":"skillDuel","why":"You haven't duelled in a while."},{"game_type":"wouldYouRather","why":"Easy laughs on the sofa."}]}
            """),
        _ => Result.Failure<ClaudeReply>(Error.Failure("ai.failed", "no answer set"))
    };
}
