using System.Reflection;
using MediatR;
using Microsoft.Extensions.DependencyInjection;
using Twogether.Application.Features.Games;

namespace Twogether.Application;

public static class DependencyInjection
{
    public static IServiceCollection AddApplication(this IServiceCollection services)
    {
        var assembly = Assembly.GetExecutingAssembly();
        services.AddMediatR(configuration => configuration.RegisterServicesFromAssembly(assembly));
        services.AddSingleton<IGameEngine, GameEngine>();
        services.AddSingleton<IGameOutcomeService, GameOutcomeService>();
        return services;
    }
}
