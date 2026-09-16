import { useMemo } from 'react';

const KIND_CLASS = {
  CURRENT: 'node-current',
  GAP: 'node-gap',
  TARGET: 'node-target',
};

const BASIS_LABEL = {
  TAXONOMY: 'stated by the reference taxonomy',
  RETRIEVED_DOCUMENT: 'supported by a retrieved document',
  AI_SUGGESTED: "the AI's own suggestion",
};

const RELATION_LABEL = {
  PREREQUISITE_OF: 'comes before',
  COMPONENT_OF: 'is part of',
  RELATED_TO: 'relates to',
  REQUIRED_FOR_ROLE: 'is needed for',
};

/**
 * The skill graph, drawn from the same data the plan was ordered by.
 *
 * <p>The picture is a view of the graph, not the graph itself: the ordering happened on the
 * server, in a topological sort over these prerequisite edges, and the phases were built to
 * respect it. That matters for how this should be read - moving a box here would not move a phase.
 *
 * <p>Columns are prerequisite depth: a skill sits one column to the right of everything that must
 * come before it. Drawn in plain SVG rather than with a graph library because the shape is a small
 * layered DAG, and a layout engine would add a dependency and a rendering surprise for no gain.
 */
export default function KnowledgeGraphView({ graph }) {
  const layout = useMemo(() => buildLayout(graph), [graph]);

  if (!graph?.nodes?.length) {
    return <p className="muted-note">No skill graph was produced for this plan.</p>;
  }

  const { columns, positions, width, height, prerequisiteEdges } = layout;

  return (
    <div className="graph-view">
      <p className="graph-caption">
        Arrows are prerequisites. The phases were ordered by a topological sort over these edges,
        so a skill never appears in the plan before the skills pointing into it.
      </p>

      <div className="graph-legend">
        <span className="legend-item"><i className="legend-dot node-current" /> already evidenced</span>
        <span className="legend-item"><i className="legend-dot node-gap" /> to learn</span>
        <span className="legend-item"><i className="legend-dot node-target" /> the role</span>
      </div>

      <div className="graph-scroll">
        <svg width={width} height={height} className="graph-svg" role="img"
             aria-label="Skill prerequisite graph">
          <defs>
            <marker id="graph-arrow" viewBox="0 0 10 10" refX="9" refY="5"
                    markerWidth="6" markerHeight="6" orient="auto-start-reverse">
              <path d="M 0 0 L 10 5 L 0 10 z" className="graph-arrowhead" />
            </marker>
          </defs>

          {prerequisiteEdges.map((edge, index) => {
            const from = positions.get(edge.from);
            const to = positions.get(edge.to);
            if (!from || !to) return null;
            const x1 = from.x + from.width;
            const y1 = from.y + from.height / 2;
            const x2 = to.x;
            const y2 = to.y + to.height / 2;
            const midX = (x1 + x2) / 2;
            return (
              <path
                key={`${edge.from}-${edge.to}-${index}`}
                d={`M ${x1} ${y1} C ${midX} ${y1}, ${midX} ${y2}, ${x2} ${y2}`}
                className={`graph-edge ${edge.basis === 'AI_SUGGESTED' ? 'graph-edge-suggested' : ''}`}
                markerEnd="url(#graph-arrow)"
              />
            );
          })}

          {columns.flat().map((node) => {
            const position = positions.get(node.id);
            return (
              <g key={node.id} transform={`translate(${position.x}, ${position.y})`}>
                <rect
                  width={position.width}
                  height={position.height}
                  rx="8"
                  className={`graph-node ${KIND_CLASS[node.kind] || 'node-current'}`}
                />
                <text x={position.width / 2} y={position.height / 2 + 4} className="graph-node-label">
                  {truncate(node.label, 24)}
                </text>
                <title>{node.label}{node.taxonomyCode ? ` (${node.taxonomyCode})` : ''}</title>
              </g>
            );
          })}
        </svg>
      </div>

      {graph.brokenCycles?.length > 0 && (
        <div className="inline-error">
          <div>
            {graph.brokenCycles.map((note, index) => <p key={index}>{note}</p>)}
          </div>
        </div>
      )}

      <details className="evidence-panel">
        <summary>Every relationship, and what supports it ({graph.edges?.length || 0})</summary>
        <ul className="edge-list">
          {(graph.edges || []).map((edge, index) => {
            const from = graph.nodes.find((n) => n.id === edge.from);
            const to = graph.nodes.find((n) => n.id === edge.to);
            return (
              <li key={index}>
                <span className="edge-statement">
                  <strong>{from?.label || edge.from}</strong>{' '}
                  {RELATION_LABEL[edge.relation] || edge.relation}{' '}
                  <strong>{to?.label || edge.to}</strong>
                </span>
                {/* SFIA describes professional skills and levels; it does not say which technology
                    precedes another. An edge the AI reasoned out on its own says so here rather
                    than borrowing the framework's authority. */}
                <span className={`basis-pill basis-${(edge.basis || '').toLowerCase()}`}>
                  {BASIS_LABEL[edge.basis] || edge.basis}
                </span>
                {edge.basisNote && <p className="muted-note">{edge.basisNote}</p>}
              </li>
            );
          })}
        </ul>
      </details>
    </div>
  );
}

const NODE_WIDTH = 168;
const NODE_HEIGHT = 44;
const COLUMN_GAP = 96;
const ROW_GAP = 18;

/**
 * Assigns each node to a column by its longest prerequisite chain, then stacks the columns.
 *
 * <p>Longest path rather than shortest: a node must sit to the right of everything that precedes
 * it, and the shortest path would place a skill in front of one of its own prerequisites whenever
 * a second, longer route existed. The walk carries a visited set because a cycle the server could
 * not break would otherwise recurse forever in the browser.
 */
function buildLayout(graph) {
  const nodes = graph?.nodes || [];
  const edges = graph?.edges || [];
  const prerequisiteEdges = edges.filter((e) => e.relation === 'PREREQUISITE_OF');

  const incoming = new Map(nodes.map((n) => [n.id, []]));
  prerequisiteEdges.forEach((edge) => {
    if (incoming.has(edge.to)) incoming.get(edge.to).push(edge.from);
  });

  const depthCache = new Map();
  const depthOf = (id, visiting) => {
    if (depthCache.has(id)) return depthCache.get(id);
    if (visiting.has(id)) return 0;
    visiting.add(id);
    const parents = incoming.get(id) || [];
    const depth = parents.length === 0
      ? 0
      : Math.max(...parents.map((parent) => depthOf(parent, visiting) + 1));
    visiting.delete(id);
    depthCache.set(id, depth);
    return depth;
  };

  const byDepth = new Map();
  nodes.forEach((node) => {
    // The role itself belongs at the end regardless of how the model wired it up; it is the
    // destination, not a step.
    const depth = node.kind === 'TARGET' ? Number.MAX_SAFE_INTEGER : depthOf(node.id, new Set());
    if (!byDepth.has(depth)) byDepth.set(depth, []);
    byDepth.get(depth).push(node);
  });

  const orderedDepths = [...byDepth.keys()].sort((a, b) => a - b);
  const columns = orderedDepths.map((depth) => byDepth.get(depth));

  const positions = new Map();
  const tallest = Math.max(1, ...columns.map((column) => column.length));
  const height = tallest * (NODE_HEIGHT + ROW_GAP) + ROW_GAP;

  columns.forEach((column, columnIndex) => {
    const columnHeight = column.length * (NODE_HEIGHT + ROW_GAP) - ROW_GAP;
    const offsetY = (height - columnHeight) / 2;
    column.forEach((node, rowIndex) => {
      positions.set(node.id, {
        x: columnIndex * (NODE_WIDTH + COLUMN_GAP) + 8,
        y: offsetY + rowIndex * (NODE_HEIGHT + ROW_GAP),
        width: NODE_WIDTH,
        height: NODE_HEIGHT,
      });
    });
  });

  const width = Math.max(320, columns.length * (NODE_WIDTH + COLUMN_GAP) + 16);
  return { columns, positions, width, height, prerequisiteEdges };
}

function truncate(text, max) {
  if (!text) return '';
  return text.length > max ? `${text.slice(0, max - 1)}…` : text;
}
