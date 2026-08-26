namespace Twogether.Core.Seedwork;

public abstract class BaseEntity
{
    public Guid Id { get; protected set; } = Guid.NewGuid();
    public DateTime CreatedAtUtc { get; protected set; }
    public DateTime UpdatedAtUtc { get; protected set; }
}
