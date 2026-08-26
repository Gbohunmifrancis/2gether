FROM mcr.microsoft.com/dotnet/sdk:10.0 AS build
WORKDIR /src

COPY Twogether.slnx ./
COPY Directory.Build.props ./
COPY src ./src
COPY tests ./tests
RUN dotnet restore Twogether.slnx
RUN dotnet publish src/Twogether.Api/Twogether.Api.csproj -c Release -o /app/publish --no-restore

FROM mcr.microsoft.com/dotnet/aspnet:10.0 AS runtime
WORKDIR /app
COPY --from=build /app/publish .
ENV ASPNETCORE_ENVIRONMENT=Production
EXPOSE 8080
ENTRYPOINT ["dotnet", "Twogether.Api.dll"]
