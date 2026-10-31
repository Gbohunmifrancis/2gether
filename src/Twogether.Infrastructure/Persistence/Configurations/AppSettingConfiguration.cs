using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using Twogether.Core.Entities;

namespace Twogether.Infrastructure.Persistence.Configurations;

public sealed class AppSettingConfiguration : IEntityTypeConfiguration<AppSetting>
{
    public void Configure(EntityTypeBuilder<AppSetting> builder)
    {
        builder.ToTable("app_settings");
        builder.HasKey(item => item.Key);
        builder.Property(item => item.Key).HasMaxLength(AppSetting.MaxKeyLength);
        builder.Property(item => item.Value).HasMaxLength(AppSetting.MaxValueLength).IsRequired();
    }
}
