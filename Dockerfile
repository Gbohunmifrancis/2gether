FROM mcr.microsoft.com/dotnet/sdk:10.0 AS build
WORKDIR /src

# version.txt is written by the deploy pipeline (the commit being deployed); the [t] makes it optional.
COPY Twogether.slnx Directory.Build.props version.tx[t] ./
COPY src ./src
COPY tests ./tests
RUN dotnet restore Twogether.slnx
RUN dotnet publish src/Twogether.Api/Twogether.Api.csproj -c Release -o /app/publish --no-restore \
    && if [ -f version.txt ]; then cp version.txt /app/publish/version.txt; fi

FROM mcr.microsoft.com/dotnet/aspnet:10.0 AS runtime
WORKDIR /app
COPY --from=build /app/publish .
ENV ASPNETCORE_ENVIRONMENT=Production
EXPOSE 8080
ENTRYPOINT ["dotnet", "Twogether.Api.dll"]
