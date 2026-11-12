# ADR-0004: Low-poly 3D rendered as pixel art

Date: 2026-10-20 · Status: Accepted. Visual spike (I1a, branch `spike/visual`) approved by the director on 2026-10-20.

## Context
Visual direction: isometric, detailed pixel art, a dark teal and industrial grey palette with cyan and orange accents. Hand-drawn sprites need an artist for every object, orientation and animation frame; the project has none.

## Decision
Three.js with an orthographic isometric camera, rendered to a low-resolution target and upscaled with nearest-neighbour filtering. Objects are built from code and data (primitives, palette) with dynamic lights and emissive indicators. A `Renderable` boundary allows replacing individual objects with artist sprites later.

## Consequences
New rooms and machines cost a description, not a drawing session. Characters are stylized and blocky. Quality comes from palette, lighting and shader consistency. No proprietary asset is copied or traced.

## Reopen if
The visual spike does not convince the director, or an artist joins.
