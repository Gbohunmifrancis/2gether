using Twogether.Core.Enums;
using Twogether.Shared.Localization;

namespace Twogether.Application.Features.Games;

/// <summary>
/// Connect four: 7 columns by 6 rows, a disc drops to the lowest free row, four in a row in any direction; a match of
/// up to three games, first to two wins (docs/games.md).
/// </summary>
public sealed class ConnectFourRules : GridMatchRules
{
    public override GameType Type => GameType.ConnectFour;
    protected override int Columns => 7;
    protected override int Rows => 6;
    protected override int InARow => 4;
    protected override int GamesInMatch => 3;
    protected override int WinsNeeded => 2;
    protected override string MoveAction => "drop";

    protected override int Land(GridMatchState state, int? index)
    {
        if (index is not { } column || column < 0 || column >= state.Columns) throw new InvalidOperationException(Loc.T("Pick a column on the board."));
        for (var row = state.Rows - 1; row >= 0; row--)
        {
            var cell = row * state.Columns + column;
            if (state.Cells[cell] is null) return cell;
        }
        throw new InvalidOperationException(Loc.T("That column is full."));
    }
}
