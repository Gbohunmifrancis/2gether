namespace Twogether.Application.Common.Interfaces;

public interface ICurrentUser
{
    Guid? UserId { get; }
    Guid? CoupleId { get; }
    bool IsAuthenticated { get; }
}
