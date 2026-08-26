namespace Twogether.Application.Common.Interfaces;

public interface ITokenService
{
    string CreateAccessToken(Guid userId, Guid? coupleId, string email, string displayName, DateTime utcNow);
}
