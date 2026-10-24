using System.Globalization;
using Twogether.Application.Features.Exercise;
using Twogether.Core.Enums;
using Twogether.Shared.Localization;

namespace Twogether.UnitTests;

public sealed class LocalizationTests
{
    [Fact]
    public void English_is_the_code_s_own_text_with_placeholders_filled()
    {
        using var _ = Loc.Use("en");
        Assert.Equal("Use at least 8 characters.", Loc.T("Use at least {min} characters.", ("min", 8)));
        Assert.Equal("1 rep", Loc.Plural(1, "{count} rep", "{count} reps"));
        Assert.Equal("0 reps", Loc.Plural(0, "{count} rep", "{count} reps"));
    }

    [Fact]
    public void Use_switches_the_language_and_restores_it()
    {
        var before = CultureInfo.CurrentUICulture;
        using (Loc.Use("pt-BR")) Assert.Equal("pt", Loc.Language);
        Assert.Equal(before, CultureInfo.CurrentUICulture);
        using (Loc.Use("xx")) Assert.Equal("en", Loc.Language);
    }

    [Fact]
    public void Arabic_has_six_counting_forms_and_french_counts_zero_as_one()
    {
        Assert.Equal(["zero", "one", "two", "few", "many", "other"], new long[] { 0, 1, 2, 5, 11, 100 }.Select(n => Loc.Category("ar", n)));
        Assert.Equal("one", Loc.Category("fr", 0));
        Assert.Equal("other", Loc.Category("es", 0));
    }

    [Fact]
    public void Untranslated_text_falls_back_to_english()
    {
        using var _ = Loc.Use("fr");
        Assert.Equal("Not a key anywhere", Loc.T("Not a key anywhere"));
    }

    [Fact]
    public void Catalog_dare_titles_are_translated_and_typed_ones_kept()
    {
        using var _ = Loc.Use("en");
        Assert.Equal("Squats", ExerciseText.Title("Squats", ExerciseType.Squat));
        Assert.Equal("Morning burn", ExerciseText.Title("Morning burn", ExerciseType.Squat));
        Assert.Equal("30 s", ExerciseText.Target(30, ExerciseType.Plank));
        Assert.Equal("15 reps", ExerciseText.Target(15, ExerciseType.Squat));
    }

    [Theory]
    [InlineData("fr")]
    [InlineData("pt")]
    [InlineData("es")]
    [InlineData("ar")]
    [InlineData("hi")]
    public void Every_catalog_is_embedded(string language)
    {
        using var stream = typeof(Loc).Assembly.GetManifestResourceStream($"Twogether.Shared.Localization.i18n.{language}.json");
        Assert.NotNull(stream);
    }
}
