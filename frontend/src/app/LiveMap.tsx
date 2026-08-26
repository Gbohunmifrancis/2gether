"use client";

import { useEffect, useRef, useState } from "react";
import type { CoupleLocation } from "@/lib/api";
import "maplibre-gl/dist/maplibre-gl.css";

export function LiveMap({ locations, partnerColor }: { locations: CoupleLocation[]; partnerColor: string }) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<import("maplibre-gl").Map | null>(null);
  const markersRef = useRef<import("maplibre-gl").Marker[]>([]);
  const [mapReady, setMapReady] = useState(false);

  useEffect(() => {
    if (!containerRef.current || mapRef.current) return;
    let disposed = false;
    void import("maplibre-gl").then(({ default: maplibregl }) => {
      if (disposed || !containerRef.current) return;
      const first = locations[0];
      mapRef.current = new maplibregl.Map({
        container: containerRef.current,
        // OpenFreeMap's Liberty style includes real roads, place labels and
        // neighbourhoods while remaining keyless for a private app.
        style: "https://tiles.openfreemap.org/styles/liberty",
        center: first ? [first.longitude, first.latitude] : [3.3792, 6.5244],
        zoom: first ? 14 : 10,
        attributionControl: { compact: true },
      });
      mapRef.current.addControl(new maplibregl.NavigationControl({ showCompass: false }), "top-right");
      mapRef.current.once("load", () => setMapReady(true));
    });
    return () => { disposed = true; mapRef.current?.remove(); mapRef.current = null; };
  }, []); // map is intentionally created once

  useEffect(() => {
    const map = mapRef.current;
    if (!map || !mapReady) return;
    void import("maplibre-gl").then(({ default: maplibregl }) => {
      markersRef.current.forEach((marker) => marker.remove());
      markersRef.current = locations.map((location) => {
        const element = document.createElement("div");
        element.className = `bitmoji-marker ${location.isCurrentUser ? "mine" : "partner"}`;
        element.style.setProperty("--marker-color", location.isCurrentUser ? "#f45c91" : partnerColor);
        element.innerHTML = `<span>${escapeHtml(location.displayName.charAt(0).toUpperCase())}</span><small>${escapeHtml(location.displayName)}</small>`;
        return new maplibregl.Marker({ element }).setLngLat([location.longitude, location.latitude]).addTo(map);
      });
      if (locations.length === 1) map.easeTo({ center: [locations[0].longitude, locations[0].latitude], zoom: Math.max(map.getZoom(), 14) });
      if (locations.length > 1) {
        const bounds = new maplibregl.LngLatBounds();
        locations.forEach((location) => bounds.extend([location.longitude, location.latitude]));
        map.fitBounds(bounds, { padding: 90, maxZoom: 16, duration: 900 });
      }
    });
  }, [locations, mapReady, partnerColor]);

  return <div className="live-map" ref={containerRef} aria-label="Live partner map" />;
}

function escapeHtml(value: string) {
  return value.replace(/[&<>'"]/g, (character) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;" })[character] ?? character);
}
