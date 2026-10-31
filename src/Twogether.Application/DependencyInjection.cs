using System.Reflection;
using MediatR;
using Microsoft.Extensions.DependencyInjection;
using Twogether.Application.Features.Account;
using Twogether.Application.Features.Billing;
using Twogether.Application.Features.Games;

namespace Twogether.Application;

public static class DependencyInjection
{
    public static IServiceCollection AddApplication(this IServiceCollection services)
    {
        var assembly = Assembly.GetExecutingAssembly();
        services.AddMediatR(configuration => configuration.RegisterServicesFromAssembly(assembly));
        services.AddSingleton<IGameEngine, GameEngine>();
        services.AddSingleton<IGameSetupFactory, GameSetupFactory>();
        services.AddSingleton<IGameOutcomeService, GameOutcomeService>();
        services.AddScoped<IEntitlementService, EntitlementService>();
        services.AddScoped<GooglePlaySubscriptionSync>();
        services.AddScoped<BachsCheckoutService>();
        services.AddScoped<GoogleExternalTransactionSender>();
        services.AddScoped<AccountExportService>();
        services.AddScoped<AvatarService>();
        services.AddScoped<Twogether.Application.Features.Config.FeatureFlagService>();
        services.AddScoped<Twogether.Application.Features.Admin.AdminAnalytics>();
        services.AddScoped<Twogether.Application.Features.Admin.Insights.AdminInsights>();
        return services;
    }
}
