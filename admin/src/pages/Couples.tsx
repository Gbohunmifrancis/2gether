import { useQuery } from "@tanstack/react-query";
import { getRouteApi, Link } from "@tanstack/react-router";
import type { CSSProperties } from "react";
import { queries } from "../api/queries";
import type { TopCouple } from "../api/types";
import { Characters, MiniHeart } from "../components/Art";
import { Icon } from "../components/Icon";
import { RangePicker } from "../components/RangePicker";
import { Empty, ErrorState, LoadingCards, PageHead } from "../components/ui";
import { count, date, plural } from "../lib/format";

const route = getRouteApi("/_app/couples");

/** Up to 10 hearts drawn, then a count: enough to feel, not to clutter. */
function Hearts({ hearts }: { hearts: number }) {
  const shown = Math.min(10, hearts);
  return (
    <div className="heart-row" aria-label={plural(hearts, "heart")}>
      {Array.from({ length: shown }, (_, index) => (
        <span key={index} className="heart-pop" style={{ "--i": index } as CSSProperties}>
          <MiniHeart size={17} />
        </span>
      ))}
      {hearts > shown ? <span className="small muted">+{count(hearts - shown)}</span> : null}
    </div>
  );
}

function CoupleSticker({ couple, rank }: { couple: TopCouple; rank: number }) {
  const tilt = [-2.2, 1.6, -1.1, 2.4, -1.8, 0.9][rank % 6]!;
  return (
    <Link
      to="/users"
      search={{ query: couple.coupleId, page: 1 }}
      className={`couple-sticker${rank < 3 ? " podium" : ""}`}
      style={{ "--tilt": `${tilt}deg`, "--i": rank } as CSSProperties}
      aria-label={`${couple.names[0]} and ${couple.names[1]}: ${plural(couple.hearts, "heart")}, open their accounts`}
    >
      <span className="rank">{rank + 1}</span>
      {couple.plus ? <span className="plus-tag">Plus</span> : null}
      <Characters who="both" pose={rank === 0 ? "cheer" : rank < 3 ? "wave" : "hug"} size={74} sticker={false} />
      <b className="couple-names">
        {couple.names[0]} <span className="amp">&amp;</span> {couple.names[1]}
      </b>
      <Hearts hearts={couple.hearts} />
      <div className="couple-stats">
        <span title="Dares set">
          <Icon name="dares" /> {count(couple.daresSet)}
        </span>
        <span title="Games played">
          <Icon name="games" /> {count(couple.games)}
        </span>
        <span title="Current streak" className={couple.streak >= 7 ? "hot" : undefined}>
          <Icon name="flame" /> {couple.streak}
        </span>
      </div>
      <small className="faint">together since {date(couple.linkedAtUtc)}</small>
    </Link>
  );
}

export function CouplesPage() {
  const { days } = route.useSearch();
  const { data, error, isPending, refetch } = useQuery(queries.topCouples(days, 12));

  return (
    <>
      <PageHead title="Top couples" sub={`The couples making the most hearts in the last ${days} days: both partners doing the dares the other set.`}>
        <RangePicker days={days} ranges={[7, 30, 90]} />
      </PageHead>
      {error ? (
        <ErrorState error={error} retry={() => void refetch()} />
      ) : isPending ? (
        <LoadingCards />
      ) : data.length === 0 ? (
        <div className="card">
          <Empty>No hearts made in this window yet.</Empty>
        </div>
      ) : (
        <div className="sticker-wall">
          {data.map((couple, index) => (
            <CoupleSticker key={couple.coupleId} couple={couple} rank={index} />
          ))}
        </div>
      )}
    </>
  );
}
