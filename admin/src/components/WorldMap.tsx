import { geoEqualEarth, geoPath, type GeoPermissibleObjects } from "d3-geo";
import { useEffect, useMemo, useState, type CSSProperties } from "react";
import { feature } from "topojson-client";
import type { GeometryCollection, Topology } from "topojson-specification";
import type { CountryRow } from "../api/types";
import { countryName, flag, numericCode, SMALL_PLACES } from "../lib/countries";
import { count } from "../lib/format";
import { rate } from "../lib/insights";
import { useWidth } from "./charts";

/* Countries as soft land, people as bubbles: size by people, colour by how many of their couples are on Plus. The
   shapes (world-atlas, 110m) load only when the map is shown. */

interface Shape {
  id: string;
  name: string;
  d: string;
  centroid: [number, number];
}

const W = 960;
const H = 420;
const projection = geoEqualEarth().fitExtent(
  [
    [8, 8],
    [W - 8, H - 8],
  ],
  // The inhabited world: Antarctica is left off, so fit from 56°S to the top of Greenland.
  {
    type: "MultiPoint",
    coordinates: [
      [-180, 0],
      [180, 0],
      [0, -56],
      [0, 84],
      [-170, -56],
      [170, 84],
    ],
  } as GeoPermissibleObjects,
);

function useShapes() {
  const [shapes, setShapes] = useState<Shape[] | null>(null);
  useEffect(() => {
    let live = true;
    void import("world-atlas/countries-110m.json").then((module) => {
      if (!live) return;
      const topology = (module.default ?? module) as unknown as Topology<{ countries: GeometryCollection<{ name: string }> }>;
      const collection = feature(topology, topology.objects.countries);
      const path = geoPath(projection);
      setShapes(
        collection.features
          .filter((item) => item.properties?.name !== "Antarctica")
          .map((item) => ({
            id: String(item.id ?? (item.properties?.name === "Kosovo" ? "-99" : "")),
            name: item.properties?.name ?? "",
            d: path(item) ?? "",
            centroid: path.centroid(item) as [number, number],
          })),
      );
    });
    return () => {
      live = false;
    };
  }, []);
  return shapes;
}

/** Where a country's bubble goes: its shape's middle, or a known point for small places. */
function place(code: string, shapes: Shape[]): [number, number] | null {
  const numeric = numericCode(code);
  const shape = numeric ? shapes.find((item) => item.id === numeric) : undefined;
  // A few shapes' middles fall in the sea or in far-off territories; nudge to the mainland.
  const nudges: Record<string, [number, number]> = { US: [-98, 39], FR: [2.3, 46.6], NO: [9, 61], NZ: [172, -41.5], CA: [-100, 56], RU: [60, 58] };
  const point = nudges[code] ?? (shape ? null : SMALL_PLACES[code]);
  if (point) return projection(point) as [number, number];
  return shape && Number.isFinite(shape.centroid[0]) ? shape.centroid : null;
}

export function WorldMap({ rows }: { rows: CountryRow[] }) {
  const shapes = useShapes();
  const [ref, width] = useWidth<HTMLDivElement>();
  const [hover, setHover] = useState<string | null>(null);
  const scale = width / W;
  const max = Math.max(1, ...rows.map((row) => row.people));
  const withPeople = useMemo(() => new Set(rows.map((row) => numericCode(row.code))), [rows]);
  const bubbles = useMemo(() => {
    if (!shapes) return [];
    return rows
      .map((row) => ({ row, at: place(row.code, shapes) }))
      .filter((item): item is { row: CountryRow; at: [number, number] } => item.at !== null)
      .sort((a, b) => b.row.people - a.row.people);
  }, [rows, shapes]);
  const active = hover ? bubbles.find((item) => item.row.code === hover) : null;

  return (
    <div className="world" ref={ref} onPointerLeave={() => setHover(null)}>
      <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label="Map of where people are; the table view has every country">
        <g className="land">
          {(shapes ?? []).map((shape) => (
            <path key={shape.id + shape.name} d={shape.d} className={withPeople.has(shape.id) ? "has" : undefined} />
          ))}
        </g>
        <g className="bubbles">
          {bubbles.map(({ row, at }, index) => {
            const radius = 4 + Math.sqrt(row.people / max) * 34;
            const share = row.couples > 0 ? row.plus / row.couples : 0;
            return (
              <circle
                key={row.code}
                cx={at[0]}
                cy={at[1]}
                r={radius}
                className={`bubble${hover === row.code ? " on" : ""}`}
                style={
                  {
                    "--plus": `${Math.round(Math.min(1, share * 2.5) * 100)}%`,
                    "--i": index,
                  } as CSSProperties
                }
                onPointerEnter={() => setHover(row.code)}
                tabIndex={0}
                onFocus={() => setHover(row.code)}
                aria-label={`${countryName(row.code)}: ${count(row.people)} people`}
              />
            );
          })}
        </g>
      </svg>
      {!shapes ? <div className="world-loading small faint">Unfolding the map…</div> : null}
      {active ? (
        <div
          className="hover-card"
          style={{
            left: Math.min(Math.max(active.at[0] * scale, 120), width - 120),
            top: active.at[1] * scale - (4 + Math.sqrt(active.row.people / max) * 34) * scale - 10,
          }}
        >
          <div className="hover-title">
            <span className="flag big">{flag(active.row.code)}</span>
            <b>{countryName(active.row.code)}</b>
          </div>
          <dl>
            <dt>People</dt>
            <dd>{count(active.row.people)}</dd>
            <dt>Couples</dt>
            <dd>{count(active.row.couples)}</dd>
            <dt>On Plus</dt>
            <dd>
              {count(active.row.plus)} <span className="faint">{rate(active.row.couples ? active.row.plus / active.row.couples : null)}</span>
            </dd>
            <dt>Active this week</dt>
            <dd>{count(active.row.activeWeek)}</dd>
          </dl>
        </div>
      ) : null}
    </div>
  );
}
