using System.Reflection;
using Microsoft.EntityFrameworkCore;
using Twogether.Application.Features.Food;
using Twogether.Core.Entities;
using Twogether.Core.Users;
using Twogether.Infrastructure.Persistence;

namespace Twogether.UnitTests;

public sealed class ContentRegionTests
{
    private static readonly DateTime Now = new(2026, 10, 23, 9, 0, 0, DateTimeKind.Utc);

    [Fact]
    public void Every_assigned_country_is_known_and_nothing_else()
    {
        Assert.Equal(249, ContentRegions.Countries.Count);
        Assert.Equal("NG", ContentRegions.Normalize(" ng "));
        Assert.Null(ContentRegions.Normalize("XX"));
        Assert.Null(ContentRegions.Normalize("NGA"));
        Assert.Null(ContentRegions.Normalize(""));
        Assert.Null(ContentRegions.Normalize(null));
    }

    [Theory]
    [InlineData("NG", "africa")]
    [InlineData("ke", "africa")]
    [InlineData("ZA", "africa")]
    [InlineData("EG", "mena")]
    [InlineData("MA", "mena")]
    [InlineData("SA", "mena")]
    [InlineData("IN", "southasia")]
    [InlineData("PK", "southasia")]
    [InlineData("JP", "eastasia")]
    [InlineData("ID", "eastasia")]
    [InlineData("BR", "latam")]
    [InlineData("MX", "latam")]
    [InlineData("US", "default")]
    [InlineData("GB", "default")]
    [InlineData(null, "default")]
    [InlineData("XX", "default")]
    public void Countries_map_to_their_region(string? country, string region) => Assert.Equal(region, ContentRegions.For(country));

    [Fact]
    public void Every_mapped_code_is_a_real_country()
    {
        var map = (Dictionary<string, string>)typeof(ContentRegions).GetField("RegionByCountry", BindingFlags.NonPublic | BindingFlags.Static)!.GetValue(null)!;
        Assert.All(map.Keys, code => Assert.Contains(code, ContentRegions.Countries));
    }

    [Fact]
    public void A_user_keeps_only_a_real_country()
    {
        var user = User.Create("a@example.com", "Ada", new DateOnly(1995, 5, 1), Now);
        Assert.Null(user.CountryCode);
        user.SetCountry("gh", Now);
        Assert.Equal("GH", user.CountryCode);
        Assert.Throws<ArgumentException>(() => user.SetCountry("Ghana", Now));
        Assert.Equal("GH", user.CountryCode);
    }

    [Fact]
    public void The_food_prompt_names_the_country_only_when_known()
    {
        Assert.Contains("ISO 3166-1 code NG", FoodPrompt.UserText(null, "ng"));
        Assert.DoesNotContain("ISO", FoodPrompt.UserText(null, null));
        Assert.DoesNotContain("ISO", FoodPrompt.UserText(null, "Narnia"));
        Assert.EndsWith("Notes from the person who ate it: two scoops", FoodPrompt.UserText("two scoops", "IN"));
    }

    [Fact]
    public void The_user_mapping_stores_the_country_and_a_female_gender()
    {
        using var context = new ApplicationDbContext(new DbContextOptionsBuilder<ApplicationDbContext>().UseNpgsql("Host=localhost;Database=model-only").Options);
        var entity = context.Model.FindEntityType(typeof(User))!;
        Assert.Equal(2, entity.FindProperty(nameof(User.CountryCode))!.GetMaxLength());
        Assert.True(entity.FindProperty(nameof(User.CountryCode))!.IsNullable);
        // A database default here made EF skip Female (the enum's zero) on insert, saving everyone as PreferNotToSay.
        Assert.Equal(Microsoft.EntityFrameworkCore.Metadata.ValueGenerated.Never, entity.FindProperty(nameof(User.Gender))!.ValueGenerated);
    }
}
