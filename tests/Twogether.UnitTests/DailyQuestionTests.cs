using Twogether.Application.Features.Questions;
using Twogether.Core.Entities;
using Twogether.Core.Questions;

namespace Twogether.UnitTests;

public sealed class DailyQuestionTests
{
    private static readonly DateTime Now = new(2026, 10, 10, 9, 0, 0, DateTimeKind.Utc);
    private static readonly DateOnly Today = new(2026, 10, 10);

    [Fact]
    public void Catalog_keys_are_unique_short_and_resolvable()
    {
        var keys = DailyQuestionCatalog.All.Select(item => item.Key).ToList();
        Assert.True(keys.Count >= 60);
        Assert.Equal(keys.Count, keys.Distinct().Count());
        Assert.All(keys, key => Assert.InRange(key.Length, 1, DailyQuestion.MaxKeyLength));
        Assert.All(DailyQuestionCatalog.All, item => Assert.False(string.IsNullOrWhiteSpace(item.Text)));
        Assert.Null(DailyQuestionCatalog.Find("no-such-key"));
    }

    [Fact]
    public void Picker_is_stable_per_couple_and_never_repeats_until_the_bank_runs_out()
    {
        var keys = DailyQuestionCatalog.All.Select(item => item.Key).ToList();
        var couple = Guid.Parse("8f6c1e0a-3b7d-4c55-9a51-0d2b6f7e9c10");
        var other = Guid.Parse("1a2b3c4d-0000-4000-8000-000000000001");
        Assert.Equal(DailyQuestionPicker.Pick(couple, keys, new Dictionary<string, DateOnly>()), DailyQuestionPicker.Pick(couple, keys, new Dictionary<string, DateOnly>()));

        var used = new Dictionary<string, DateOnly>();
        var otherUsed = new Dictionary<string, DateOnly>();
        var sameDayMatches = 0;
        for (var day = 0; day < keys.Count; day++)
        {
            var pick = DailyQuestionPicker.Pick(couple, keys, used);
            Assert.False(used.ContainsKey(pick));
            used[pick] = Today.AddDays(day);
            var otherPick = DailyQuestionPicker.Pick(other, keys, otherUsed);
            otherUsed[otherPick] = Today.AddDays(day);
            if (otherPick == pick) sameDayMatches++;
        }
        Assert.Equal(keys.Count, used.Count);
        Assert.True(sameDayMatches < keys.Count / 4, "two couples should not walk the bank in the same order");

        // Bank exhausted: the question used longest ago comes back first.
        var oldest = used.MinBy(item => item.Value).Key;
        Assert.Equal(oldest, DailyQuestionPicker.Pick(couple, keys, used));
    }

    [Fact]
    public void Streak_counts_days_both_answered_and_tolerates_an_open_today()
    {
        var days = new[] { Today.AddDays(-1), Today.AddDays(-2), Today.AddDays(-3), Today.AddDays(-5) };
        Assert.Equal(3, DailyQuestionStreak.CurrentStreakDays(days, Today));
        Assert.Equal(4, DailyQuestionStreak.CurrentStreakDays(days.Append(Today), Today));
        Assert.Equal(0, DailyQuestionStreak.CurrentStreakDays([Today.AddDays(-2)], Today));
    }

    [Fact]
    public void Partner_answer_unlocks_only_after_the_viewer_answers()
    {
        var coupleId = Guid.NewGuid();
        var ada = Guid.NewGuid();
        var femi = Guid.NewGuid();
        var names = new Dictionary<Guid, string> { [ada] = "Ada", [femi] = "Femi" };
        var question = DailyQuestion.Create(coupleId, Today, DailyQuestionCatalog.All[0].Key, Now);
        var femiAnswer = DailyAnswer.Create(question.Id, coupleId, femi, "Coffee with you", Now);

        var locked = DailyQuestionMapper.ToDto(question, ada, [femiAnswer], names, 2);
        Assert.True(locked.PartnerAnswered);
        Assert.Null(locked.PartnerAnswer);
        Assert.Null(locked.MyAnswer);
        Assert.Equal(DailyQuestionCatalog.All[0].Text, locked.Text);
        Assert.Equal("2026-10-10", locked.LocalDate);

        var adaAnswer = DailyAnswer.Create(question.Id, coupleId, ada, "The long walk", Now);
        var open = DailyQuestionMapper.ToDto(question, ada, [femiAnswer, adaAnswer], names, 3);
        Assert.Equal("Coffee with you", open.PartnerAnswer!.Body);
        Assert.Equal("Femi", open.PartnerAnswer.UserName);
        Assert.Equal("The long walk", open.MyAnswer!.Body);

        var retired = DailyQuestionMapper.ToDto(DailyQuestion.Create(coupleId, Today, "retired-99", Now), ada, [], names, 0);
        Assert.Equal(DailyQuestionMapper.RetiredText, retired.Text);
        Assert.Throws<ArgumentException>(() => DailyAnswer.Create(question.Id, coupleId, ada, new string('x', 1001), Now));
    }
}
