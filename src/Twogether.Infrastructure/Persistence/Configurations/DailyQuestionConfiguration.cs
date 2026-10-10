using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using Twogether.Core.Entities;

namespace Twogether.Infrastructure.Persistence.Configurations;

public sealed class DailyQuestionConfiguration : IEntityTypeConfiguration<DailyQuestion>
{
    public void Configure(EntityTypeBuilder<DailyQuestion> builder)
    {
        builder.ToTable("daily_questions");
        builder.HasKey(item => item.Id);
        builder.Property(item => item.QuestionKey).HasMaxLength(DailyQuestion.MaxKeyLength).IsRequired();
        // One question per couple per day, even when both partners open the app at once.
        builder.HasIndex(item => new { item.CoupleId, item.LocalDate }).IsUnique();
    }
}

public sealed class DailyAnswerConfiguration : IEntityTypeConfiguration<DailyAnswer>
{
    public void Configure(EntityTypeBuilder<DailyAnswer> builder)
    {
        builder.ToTable("daily_answers");
        builder.HasKey(item => item.Id);
        builder.Property(item => item.Body).HasMaxLength(DailyAnswer.MaxBodyLength).IsRequired();
        builder.HasOne<DailyQuestion>().WithMany().HasForeignKey(item => item.DailyQuestionId).OnDelete(DeleteBehavior.Cascade);
        // One answer per person per question; editing updates it in place.
        builder.HasIndex(item => new { item.DailyQuestionId, item.UserId }).IsUnique();
        builder.HasIndex(item => item.CoupleId);
    }
}
