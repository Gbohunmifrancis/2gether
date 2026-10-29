using Twogether.Core.Enums;
using Twogether.Shared.Localization;

namespace Twogether.Application.Features.Games;

/// <summary>Tic-tac-toe: 3×3, three in a row; a match of up to five games, first to three wins (docs/games.md).</summary>
public sealed class TicTacToeRules : GridMatchRules
{
    public override GameType Type => GameType.TicTacToe;
    protected override int Columns => 3;
    protected override int Rows => 3;
    protected override int InARow => 3;
    protected override int GamesInMatch => 5;
    protected override int WinsNeeded => 3;
    protected override string MoveAction => "place";

    protected override int Land(GridMatchState state, int? index)
    {
        if (index is not { } cell || cell < 0 || cell >= state.Cells.Count) throw new InvalidOperationException(Loc.T("Pick a square on the board."));
        if (state.Cells[cell] is not null) throw new InvalidOperationException(Loc.T("That square is taken."));
        return cell;
    }
}
