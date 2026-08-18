'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { ActionIcon, Alert, Badge, Group, Text, Tooltip } from '@mantine/core';
import { useLocalStorage } from '@mantine/hooks';
import { notifications } from '@mantine/notifications';
import {
  IconAlertTriangle,
  IconArrowsMaximize,
  IconArrowsMinimize,
  IconChevronDown,
  IconChevronRight,
  IconDatabase,
  IconFocusCentered,
  IconLayoutGrid,
  IconTable,
  IconTrash,
  IconZoomIn,
  IconZoomOut
} from '@tabler/icons-react';
import { useQueries } from '@tanstack/react-query';
import {
  Background,
  BackgroundVariant,
  ConnectionMode,
  ConnectionLineType,
  Controls,
  Handle,
  MarkerType,
  MiniMap,
  Position,
  ReactFlow,
  SelectionMode,
  type Connection,
  type Edge,
  type Node,
  type NodeProps,
  type ReactFlowInstance,
  useEdgesState,
  useNodesState
} from '@xyflow/react';

import { fetchColumns } from '@/features/synthetic/hooks';
import type { DiscoveryGraphEdge, DiscoveryGraphNode } from '@/features/pii-discovery/types';
import type { DataColumn } from '@/lib/types';
import { keys } from '@/lib/keys';
import type { CrossDatabaseLink, IdentityAnchor, LoadedApplication } from './types';

type Props = {
  applications: LoadedApplication[];
  anchors: IdentityAnchor[];
  crossLinks: CrossDatabaseLink[];
  entityName?: string | null;
  onCrossLinksChange: (links: CrossDatabaseLink[]) => void;
  onRemoveTable: (applicationId: string, table: string) => void;
};

type ArchitectureRow = {
  key: string;
  column: string;
  type?: string;
  detail?: string;
  kind: 'PK' | 'FK' | 'PII' | 'COLUMN';
  input: boolean;
  output: boolean;
};

type ArchitectureNodeData = Record<string, unknown> & {
  kind: 'APPLICATION' | 'TABLE' | 'ENTITY';
  title: string;
  subtitle?: string;
  applicationId?: string;
  table?: string;
  tone?: number;
  piiCount?: number;
  rows?: ArchitectureRow[];
  expanded?: boolean;
  loadingColumns?: boolean;
  columnsError?: string | null;
  focus?: 'selected' | 'parent' | 'child' | 'dim' | null;
  onToggle?: () => void;
  onRemove?: () => void;
};

type ArchitectureFlowNode = Node<ArchitectureNodeData, 'architecture'>;
type PositionMap = Record<string, { x: number; y: number }>;

const POSITION_STORAGE_KEY = 'forgetdm.entity-architecture.positions';
const TABLE_WIDTH = 286;
const APP_GAP = 96;
const LEVEL_GAP = 92;
const ROW_GAP = 34;
const DEFAULT_ZOOM = 0.82;
const nodeTypes = { architecture: ArchitectureObjectNode };

export function ArchitectureCanvas({
  applications,
  anchors,
  crossLinks,
  entityName,
  onCrossLinksChange,
  onRemoveTable
}: Props) {
  const [expandedTables, setExpandedTables] = useState<Set<string>>(() => new Set());
  const [positionOverrides, setPositionOverrides] = useLocalStorage<PositionMap>({ key: POSITION_STORAGE_KEY, defaultValue: {} });
  const [focusNodeId, setFocusNodeId] = useState<string | null>(null);
  const [selectedLinkId, setSelectedLinkId] = useState<string | null>(null);
  const [flow, setFlow] = useState<ReactFlowInstance<ArchitectureFlowNode, Edge> | null>(null);
  const [zoom, setZoom] = useState(DEFAULT_ZOOM);
  const [expandedWorkspace, setExpandedWorkspace] = useState(false);

  const tableRefs = useMemo(() => applications.flatMap((application) => application.tables.map((table) => ({
    id: tableNodeId(application.id, table), application, table
  }))), [applications]);
  const columnQueries = useQueries({
    queries: tableRefs.map((reference) => ({
      queryKey: keys.dataSources.columns(reference.application.dataSourceId, reference.table, reference.application.schema),
      enabled: expandedTables.has(reference.id),
      queryFn: () => fetchColumns(reference.application.dataSourceId, reference.application.schema, reference.table),
      staleTime: 60_000
    }))
  });
  const columnVersion = columnQueries.map((query) => `${query.dataUpdatedAt}:${query.errorUpdatedAt}:${query.fetchStatus}`).join('|');
  const columnsByNode = useMemo(() => new Map(tableRefs.map((reference, index) => [reference.id, {
    columns: (columnQueries[index]?.data || []) as DataColumn[],
    loading: Boolean(columnQueries[index]?.isPending && expandedTables.has(reference.id)),
    error: columnQueries[index]?.error instanceof Error ? columnQueries[index].error.message : null
  }])), [columnVersion, expandedTables, tableRefs]);

  const baseModel = useMemo(() => buildFlowModel(applications, anchors, crossLinks, entityName, expandedTables, columnsByNode),
    [applications, anchors, columnsByNode, crossLinks, entityName, expandedTables]);
  const traced = useMemo(() => focusNodeId ? traceNodes(focusNodeId, baseModel.edges) : null, [baseModel.edges, focusNodeId]);

  const toggleTable = useCallback((nodeId: string) => {
    setExpandedTables((current) => {
      const next = new Set(current);
      if (next.has(nodeId)) next.delete(nodeId); else next.add(nodeId);
      return next;
    });
    setPositionOverrides({});
  }, [setPositionOverrides]);

  const modelNodes = useMemo<ArchitectureFlowNode[]>(() => baseModel.nodes.map((node) => {
    const override = positionOverrides[node.id];
    const focus = !focusNodeId || node.data.kind !== 'TABLE'
      ? null
      : node.id === focusNodeId ? 'selected'
        : traced?.parents.has(node.id) ? 'parent'
          : traced?.children.has(node.id) ? 'child' : 'dim';
    return {
      ...node,
      position: override || node.position,
      className: `${node.className || ''}${focus ? ` is-${focus}` : ''}`,
      data: {
        ...node.data,
        focus,
        onToggle: node.data.kind === 'TABLE' ? () => toggleTable(node.id) : undefined,
        onRemove: node.data.kind === 'TABLE' && node.data.applicationId && node.data.table
          ? () => onRemoveTable(node.data.applicationId as string, node.data.table as string) : undefined
      }
    };
  }), [baseModel.nodes, focusNodeId, onRemoveTable, positionOverrides, toggleTable, traced]);
  const modelEdges = useMemo<Edge[]>(() => baseModel.edges.map((edge) => ({
    ...edge,
    selected: edge.id === selectedLinkId,
    className: `${edge.className || ''}${focusNodeId && traced && !(traced.all.has(edge.source) && traced.all.has(edge.target)) ? ' is-dim' : ''}`
  })), [baseModel.edges, focusNodeId, selectedLinkId, traced]);

  const [nodes, setNodes, onNodesChange] = useNodesState<ArchitectureFlowNode>(modelNodes);
  const [edges, setEdges, onEdgesChange] = useEdgesState<Edge>(modelEdges);
  useEffect(() => setNodes(modelNodes), [modelNodes, setNodes]);
  useEffect(() => setEdges(modelEdges), [modelEdges, setEdges]);
  useEffect(() => {
    if (!expandedWorkspace) return;
    const previous = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => { document.body.style.overflow = previous; };
  }, [expandedWorkspace]);

  const autoLayout = () => {
    setPositionOverrides({});
    setNodes(baseModel.nodes);
    requestAnimationFrame(() => flow?.fitView({ padding: 0.12, duration: 320 }));
  };
  const removeSelectedLink = useCallback(() => {
    if (!selectedLinkId?.startsWith('cross:')) return;
    const id = selectedLinkId.slice('cross:'.length);
    onCrossLinksChange(crossLinks.filter((link) => link.id !== id));
    setSelectedLinkId(null);
  }, [crossLinks, onCrossLinksChange, selectedLinkId]);

  const connect = useCallback((connection: Connection) => {
    if (!connection.source || !connection.target || !connection.sourceHandle || !connection.targetHandle) {
      notifications.show({ color: 'yellow', title: 'Relationship not created', message: 'Drop the connector directly on a column port.' });
      return;
    }

    const sourceIsParent = connection.sourceHandle.startsWith('out:') && connection.targetHandle.startsWith('in:');
    const targetIsParent = connection.sourceHandle.startsWith('in:') && connection.targetHandle.startsWith('out:');
    if (!sourceIsParent && !targetIsParent) {
      notifications.show({ color: 'yellow', title: 'Choose one parent and one child port', message: 'Connect a right-side key port with a left-side foreign/reference port. You may drag in either direction.' });
      return;
    }

    const parentNodeId = sourceIsParent ? connection.source : connection.target;
    const childNodeId = sourceIsParent ? connection.target : connection.source;
    const parentHandle = sourceIsParent ? connection.sourceHandle : connection.targetHandle;
    const childHandle = sourceIsParent ? connection.targetHandle : connection.sourceHandle;
    const parent = tableReference(parentNodeId, applications);
    const child = tableReference(childNodeId, applications);
    if (!parent || !child) {
      notifications.show({ color: 'red', title: 'Relationship not created', message: 'Both connector ends must belong to tables currently on this canvas.' });
      return;
    }
    if (parentNodeId === childNodeId) {
      notifications.show({ color: 'yellow', title: 'Self-link is not allowed', message: 'Choose a column on a different table.' });
      return;
    }
    const parentColumn = decodeHandle(parentHandle, 'out:');
    const childColumn = decodeHandle(childHandle, 'in:');
    if (!parentColumn || !childColumn) {
      notifications.show({ color: 'yellow', title: 'Relationship not created', message: 'The selected column ports could not be resolved. Collapse and expand the table, then try again.' });
      return;
    }

    const catalogDuplicate = parent.application.id === child.application.id
      && (parent.application.graph?.edges || []).some((edge) => normalize(edge.from) === normalize(parent.table)
        && normalize(edge.to) === normalize(child.table)
        && (!edge.pkColumn || normalize(edge.pkColumn) === normalize(parentColumn))
        && (!edge.fkColumn || normalize(edge.fkColumn) === normalize(childColumn)));
    if (catalogDuplicate) {
      notifications.show({ color: 'blue', title: 'Catalog relationship already exists', message: 'That database relationship is already shown on the canvas.' });
      return;
    }
    const duplicate = crossLinks.some((link) => link.parentSliceId === parent.application.id
      && normalize(link.parentTable) === normalize(parent.table)
      && normalize(link.parentColumn) === normalize(parentColumn)
      && link.childSliceId === child.application.id
      && normalize(link.childTable) === normalize(child.table)
      && normalize(link.childColumn) === normalize(childColumn));
    if (duplicate) {
      notifications.show({ color: 'yellow', title: 'Relationship already exists', message: `${parentColumn} is already connected to ${childColumn}.` });
      return;
    }
    onCrossLinksChange([...crossLinks, {
      id: globalThis.crypto?.randomUUID?.() || `cross-${Date.now()}`,
      parentSliceId: parent.application.id,
      parentTable: parent.table,
      parentColumn,
      childSliceId: child.application.id,
      childTable: child.table,
      childColumn,
      kind: 'PARENT_CHILD'
    }]);
    notifications.show({
      color: 'green',
      title: parent.application.id === child.application.id ? 'Manual relationship added' : 'Cross-application relationship added',
      message: `${parent.application.label}.${parent.table}.${parentColumn} -> ${child.application.label}.${child.table}.${childColumn}`
    });
  }, [applications, crossLinks, onCrossLinksChange]);
  const deleteEdges = useCallback((deleted: Edge[]) => {
    const ids = new Set(deleted.filter((edge) => edge.id.startsWith('cross:')).map((edge) => edge.id.slice('cross:'.length)));
    if (ids.size) onCrossLinksChange(crossLinks.filter((link) => !ids.has(link.id)));
    setSelectedLinkId(null);
  }, [crossLinks, onCrossLinksChange]);

  if (!applications.length) {
    return <div className="entity-architecture-empty"><IconDatabase size={34} stroke={1.4} /><Text fw={700}>Add the first application</Text><Text c="dimmed" size="sm">Choose a source, schema, and only the tables that belong in this architecture.</Text></div>;
  }

  const boardSize = flowBoardSize(nodes, baseModel.width, baseModel.height);
  return (
    <section className={`entity-architecture-canvas${expandedWorkspace ? ' is-expanded' : ''}`}>
      {applications.some((application) => application.graphError) ? <Alert className="entity-architecture-canvas-alert" color="yellow" icon={<IconAlertTriangle size={16} />}>Tables are shown from your selection. Some relationship metadata could not be loaded, so affected catalog links are omitted.</Alert> : null}
      <div className="entity-architecture-diagram-head">
        <div><Text fw={760}>Multi-application entity architecture</Text><Text size="sm" c="dimmed">Expand tables to see every column. Connect right and left column ports in either drag direction; use Ctrl/Shift or drag an empty area to move several tables together.</Text></div>
        <Group gap="xs"><Badge variant="light">{applications.length} application{applications.length === 1 ? '' : 's'}</Badge><Badge variant="light" color="gray">{tableRefs.length} tables</Badge><Badge variant="light" color="teal">{baseModel.edges.filter((edge) => edge.id.startsWith('db:')).length} catalog relationships</Badge>{crossLinks.length ? <Badge variant="light" color="violet">{crossLinks.length} manual relationship{crossLinks.length === 1 ? '' : 's'}</Badge> : null}</Group>
      </div>
      <div className="entity-architecture-diagram-toolbar">
        <Group gap={6}>
          <Tooltip label="Zoom out" withArrow><ActionIcon variant="default" aria-label="Zoom out" onClick={() => void flow?.zoomOut({ duration: 180 })}><IconZoomOut size={16} /></ActionIcon></Tooltip>
          <Tooltip label="Zoom in" withArrow><ActionIcon variant="default" aria-label="Zoom in" onClick={() => void flow?.zoomIn({ duration: 180 })}><IconZoomIn size={16} /></ActionIcon></Tooltip>
          <Tooltip label="Fit architecture" withArrow><ActionIcon variant="default" aria-label="Fit architecture" onClick={() => void flow?.fitView({ padding: 0.12, duration: 240 })}><IconFocusCentered size={16} /></ActionIcon></Tooltip>
          <Tooltip label="Auto-layout tables" withArrow><ActionIcon variant="default" aria-label="Auto-layout tables" onClick={autoLayout}><IconLayoutGrid size={16} /></ActionIcon></Tooltip>
          <Tooltip label={expandedWorkspace ? 'Exit expanded workspace' : 'Expand workspace'} withArrow><ActionIcon variant="default" aria-label={expandedWorkspace ? 'Exit expanded workspace' : 'Expand architecture workspace'} onClick={() => setExpandedWorkspace((value) => !value)}>{expandedWorkspace ? <IconArrowsMinimize size={16} /> : <IconArrowsMaximize size={16} />}</ActionIcon></Tooltip>
          <Text size="xs" c="dimmed" ml={4}>{Math.round(zoom * 100)}%</Text>
        </Group>
        <Group gap="sm">
          {selectedLinkId?.startsWith('cross:') ? <button className="entity-architecture-delete-link" type="button" onClick={removeSelectedLink}><IconTrash size={14} /> Delete selected link</button> : null}
          <Group gap="md" className="entity-architecture-legend"><span className="entity-architecture-legend-chip is-parent">Parent chain</span><span className="entity-architecture-legend-chip is-child">Child chain</span><span className="entity-architecture-legend-chip is-cross">Manual relationship</span></Group>
        </Group>
      </div>
      <div className="entity-architecture-viewport">
        <div className="entity-architecture-flow-board" style={{ width: boardSize.width, height: boardSize.height }}>
          <ReactFlow<ArchitectureFlowNode, Edge>
            nodes={nodes} edges={edges} nodeTypes={nodeTypes} onNodesChange={onNodesChange} onEdgesChange={onEdgesChange}
            onNodeClick={(event, node) => { event.stopPropagation(); if (node.data.kind === 'TABLE') setFocusNodeId((current) => current === node.id ? null : node.id); }}
            onNodeDragStop={(_, node) => setPositionOverrides((current) => ({ ...current, [node.id]: node.position }))}
            onConnect={connect} onEdgesDelete={deleteEdges}
            onEdgeClick={(event, edge) => { event.stopPropagation(); setSelectedLinkId(edge.id.startsWith('cross:') ? edge.id : null); }}
            onPaneClick={() => { setFocusNodeId(null); setSelectedLinkId(null); }}
            onInit={(instance) => { setFlow(instance); setZoom(instance.getZoom()); }} onMoveEnd={(_, viewport) => setZoom(viewport.zoom)}
            connectionMode={ConnectionMode.Loose} connectionRadius={26} connectionLineType={ConnectionLineType.SmoothStep} defaultEdgeOptions={{ type: 'smoothstep', interactionWidth: 24, markerEnd: { type: MarkerType.ArrowClosed } }}
            selectionOnDrag selectionMode={SelectionMode.Partial} panOnDrag={[1, 2]} multiSelectionKeyCode={['Control', 'Meta', 'Shift']}
            deleteKeyCode={['Backspace', 'Delete']} minZoom={0.28} maxZoom={1.6} defaultViewport={{ x: 20, y: 20, zoom: DEFAULT_ZOOM }} zoomOnScroll={false}
          >
            <Background variant={BackgroundVariant.Dots} gap={20} size={1.1} /><Controls showInteractive={false} /><MiniMap pannable zoomable nodeColor={(node) => node.data?.kind === 'APPLICATION' ? '#dbeafe' : node.data?.tone === 1 ? '#0ca678' : node.data?.tone === 2 ? '#f59f00' : '#228be6'} />
          </ReactFlow>
        </div>
      </div>
      <div className="entity-architecture-diagram-foot">
        {focusNodeId ? <Group justify="space-between" gap="md"><Text size="xs"><b>{nodes.find((node) => node.id === focusNodeId)?.data.title}</b> connects to {traced?.parents.size || 0} parent and {traced?.children.size || 0} child table{traced?.children.size === 1 ? '' : 's'}.</Text><button type="button" onClick={() => setFocusNodeId(null)}>Clear focus</button></Group>
          : <Text size="xs" c="dimmed">Drag a table header to move it. Drag on empty canvas to select and move several tables; use middle/right drag to pan. Select a manual link and press Delete to remove it.</Text>}
      </div>
    </section>
  );
}

function ArchitectureObjectNode({ data, selected }: NodeProps<ArchitectureFlowNode>) {
  if (data.kind === 'APPLICATION') return <div className={`entity-architecture-flow-app tone-${data.tone || 0}`}><div className="entity-architecture-flow-app-icon"><IconDatabase size={18} /></div><div><b>{data.title}</b><small>{data.subtitle}</small></div></div>;
  if (data.kind === 'ENTITY') return <div className="entity-architecture-flow-entity"><small>GOVERNED BUSINESS ENTITY</small><b>{data.title}</b></div>;
  return (
    <div className={`entity-architecture-flow-table tone-${data.tone || 0}${selected ? ' is-selected' : ''}${data.focus ? ` is-${data.focus}` : ''}`}>
      <div className="entity-architecture-flow-table-head">
        <IconTable size={15} /><div><b className="entity-architecture-table-title" title={data.title}>{data.title}</b><small title={data.subtitle}>{data.subtitle}</small></div>
        <Badge size="xs" variant="light" color={data.piiCount ? 'red' : 'gray'}>{data.piiCount || 0} PII</Badge>
        <button className="nodrag nopan" type="button" aria-label={`${data.expanded ? 'Collapse' : 'Expand'} ${data.title}`} onClick={(event) => { event.stopPropagation(); data.onToggle?.(); }}>{data.expanded ? <IconChevronDown size={15} /> : <IconChevronRight size={15} />}</button>
        <button className="nodrag nopan is-delete" type="button" aria-label={`Remove ${data.title}`} onClick={(event) => { event.stopPropagation(); data.onRemove?.(); }}><IconTrash size={14} /></button>
      </div>
      <div className="entity-architecture-flow-columns">
        {data.loadingColumns ? <div className="entity-architecture-flow-empty">Loading columns...</div> : null}
        {data.columnsError ? <div className="entity-architecture-flow-empty is-error">Could not load columns</div> : null}
        {!data.loadingColumns && !data.columnsError && (data.rows || []).map((row) => <div className="entity-architecture-flow-column" key={row.key}>
          {row.input ? <Handle type="target" position={Position.Left} id={encodeHandle('in:', row.column)} className="entity-architecture-key-port is-input" title={`Foreign/reference input: ${row.column}`} /> : null}
          <span title={row.column}>{row.column}</span><small title={row.detail || row.type}>{row.kind !== 'COLUMN' ? row.kind : row.type || 'column'}</small>
          {row.output ? <Handle type="source" position={Position.Right} id={encodeHandle('out:', row.column)} className="entity-architecture-key-port is-output" title={`Parent key output: ${row.column}`} /> : null}
        </div>)}
        {!data.loadingColumns && !data.columnsError && !(data.rows || []).length ? <div className="entity-architecture-flow-empty">Expand to load all columns</div> : null}
      </div>
    </div>
  );
}

function buildFlowModel(applications: LoadedApplication[], anchors: IdentityAnchor[], crossLinks: CrossDatabaseLink[], entityName: string | null | undefined, expanded: Set<string>, columnsByNode: Map<string, { columns: DataColumn[]; loading: boolean; error: string | null }>) {
  const nodes: ArchitectureFlowNode[] = [];
  const edges: Edge[] = [];
  let appStartX = 28;
  let maxHeight = 720;
  applications.forEach((application, appIndex) => {
    const selected = new Map(application.tables.map((table) => [normalize(table), table]));
    const graphNodes = new Map((application.graph?.nodes || []).map((node) => [normalize(node.id), node]));
    const selectedEdges = (application.graph?.edges || []).flatMap((edge, index) => {
      const parent = selected.get(normalize(edge.from)); const child = selected.get(normalize(edge.to));
      return !parent || !child || normalize(parent) === normalize(child) ? [] : [{ edge, index, parent, child }];
    });
    const ids = application.tables.map((table) => tableNodeId(application.id, table));
    const relationEdges = selectedEdges.map(({ edge, parent, child }) => ({ source: tableNodeId(application.id, parent), target: tableNodeId(application.id, child), raw: edge }));
    const levels = relationshipLevels(ids, relationEdges);
    const byLevel = new Map<number, string[]>();
    ids.forEach((id) => { const level = levels.get(id) || 0; byLevel.set(level, [...(byLevel.get(level) || []), id]); });
    const maxLevel = Math.max(0, ...byLevel.keys());
    const appWidth = Math.max(500, (maxLevel + 1) * TABLE_WIDTH + maxLevel * LEVEL_GAP);
    nodes.push({ id: `app:${application.id}`, type: 'architecture', position: { x: appStartX, y: 24 }, draggable: false, selectable: false, deletable: false, className: 'entity-architecture-application-node', data: { kind: 'APPLICATION', title: application.label, subtitle: `${application.dataSourceName} / ${application.schema} / ${application.tables.length} tables`, tone: appIndex % 4 } });
    let appBottom = 120;
    [...byLevel.entries()].sort(([a], [b]) => a - b).forEach(([level, levelIds]) => {
      let y = 122;
      levelIds.sort((a, b) => a.localeCompare(b)).forEach((id) => {
        const table = application.tables.find((candidate) => tableNodeId(application.id, candidate) === id) || shortTable(id);
        const graphNode = graphNodes.get(normalize(table));
        const columnState = columnsByNode.get(id) || { columns: [], loading: false, error: null };
        const rows = architectureRows(application, table, graphNode, crossLinks, expanded.has(id) ? columnState.columns : []);
        const estimatedHeight = 58 + Math.max(1, rows.length) * 31;
        nodes.push({ id, type: 'architecture', position: { x: appStartX + level * (TABLE_WIDTH + LEVEL_GAP), y }, className: 'entity-architecture-table-node', dragHandle: '.entity-architecture-flow-table-head', deletable: false, data: { kind: 'TABLE', title: graphNode?.label || table, subtitle: `${application.label} / ${application.schema}`, applicationId: application.id, table, tone: appIndex % 4, piiCount: Number(graphNode?.piiCount || graphNode?.piiColumns?.length || 0), rows, expanded: expanded.has(id), loadingColumns: columnState.loading, columnsError: columnState.error } });
        y += estimatedHeight + ROW_GAP; appBottom = Math.max(appBottom, y);
      });
    });
    selectedEdges.forEach(({ edge, index, parent, child }) => edges.push({ id: `db:${application.id}:${edge.id || index}`, source: tableNodeId(application.id, parent), target: tableNodeId(application.id, child), sourceHandle: edge.pkColumn ? encodeHandle('out:', edge.pkColumn) : undefined, targetHandle: edge.fkColumn ? encodeHandle('in:', edge.fkColumn) : undefined, label: relationshipLabel(edge), type: 'smoothstep', deletable: false, selectable: true, className: 'entity-architecture-edge is-catalog', style: { stroke: '#94a3b8', strokeWidth: 1.5 }, markerEnd: { type: MarkerType.ArrowClosed, color: '#94a3b8' } }));
    maxHeight = Math.max(maxHeight, appBottom + 80); appStartX += appWidth + APP_GAP;
  });
  crossLinks.forEach((link) => {
    const source = tableNodeId(link.parentSliceId, link.parentTable); const target = tableNodeId(link.childSliceId, link.childTable);
    if (!nodes.some((node) => node.id === source) || !nodes.some((node) => node.id === target)) return;
    edges.push({ id: `cross:${link.id}`, source, target, sourceHandle: encodeHandle('out:', link.parentColumn), targetHandle: encodeHandle('in:', link.childColumn), label: `${link.parentColumn} -> ${link.childColumn}`, type: 'smoothstep', deletable: true, selectable: true, className: 'entity-architecture-edge is-cross', style: { stroke: '#0ca678', strokeWidth: 2.2, strokeDasharray: '7 4' }, markerEnd: { type: MarkerType.ArrowClosed, color: '#0ca678' } });
  });
  if (anchors.length) {
    const entityId = 'entity:governed';
    nodes.push({ id: entityId, type: 'architecture', position: { x: Math.max(28, (appStartX - APP_GAP) / 2 - 130), y: -86 }, draggable: true, deletable: false, className: 'entity-architecture-entity-node', data: { kind: 'ENTITY', title: entityName || 'Identity crosswalk' } });
    anchors.forEach((anchor) => { const target = tableNodeId(anchor.sliceId, anchor.table); if (nodes.some((node) => node.id === target)) edges.push({ id: `identity:${anchor.sliceId}`, source: entityId, target, label: anchor.column, deletable: false, className: 'entity-architecture-edge is-identity', style: { stroke: '#7950f2', strokeDasharray: '6 4' }, markerEnd: { type: MarkerType.ArrowClosed, color: '#7950f2' } }); });
  }
  return { nodes, edges, width: Math.max(1180, appStartX + 80), height: maxHeight };
}

function architectureRows(application: LoadedApplication, table: string, graphNode: DiscoveryGraphNode | undefined, crossLinks: CrossDatabaseLink[], allColumns: DataColumn[]) {
  const knownPk = new Set<string>(); const knownFk = new Set<string>();
  (application.graph?.edges || []).forEach((edge) => { if (normalize(edge.from) === normalize(table) && edge.pkColumn) knownPk.add(normalize(edge.pkColumn)); if (normalize(edge.to) === normalize(table) && edge.fkColumn) knownFk.add(normalize(edge.fkColumn)); });
  crossLinks.forEach((link) => { if (link.parentSliceId === application.id && normalize(link.parentTable) === normalize(table)) knownPk.add(normalize(link.parentColumn)); if (link.childSliceId === application.id && normalize(link.childTable) === normalize(table)) knownFk.add(normalize(link.childColumn)); });
  const pii = new Map((graphNode?.piiColumns || []).map((column) => [normalize(column.column), column]));
  const rows: ArchitectureRow[] = []; const seen = new Set<string>();
  const add = (row: ArchitectureRow) => { if (!row.column || seen.has(normalize(row.column))) return; seen.add(normalize(row.column)); rows.push(row); };
  if (allColumns.length) {
    allColumns.forEach((column) => { const clean = normalize(column.column); const piiColumn = pii.get(clean); const kind = knownPk.has(clean) ? 'PK' : knownFk.has(clean) ? 'FK' : piiColumn ? 'PII' : 'COLUMN'; add({ key: `column:${clean}`, column: column.column, type: column.type || '', detail: piiColumn?.piiType || column.type || '', kind, input: true, output: true }); });
  } else {
    knownPk.forEach((column) => add({ key: `pk:${column}`, column, kind: 'PK', detail: 'Parent key', input: false, output: true }));
    knownFk.forEach((column) => add({ key: `fk:${column}`, column, kind: 'FK', detail: 'Foreign/reference key', input: true, output: false }));
    pii.forEach((column, clean) => add({ key: `pii:${clean}`, column: column.column || clean, kind: 'PII', detail: column.piiType || 'PII', input: false, output: false }));
  }
  return rows;
}

function relationshipLevels(ids: string[], edges: Array<{ source: string; target: string }>) {
  const level = new Map(ids.map((id) => [id, 0])); const indegree = new Map(ids.map((id) => [id, 0])); const children = new Map(ids.map((id) => [id, [] as string[]]));
  edges.forEach((edge) => { if (!indegree.has(edge.source) || !indegree.has(edge.target)) return; indegree.set(edge.target, (indegree.get(edge.target) || 0) + 1); children.get(edge.source)?.push(edge.target); });
  const queue = ids.filter((id) => indegree.get(id) === 0); const visited = new Set<string>();
  while (queue.length) { const current = queue.shift() as string; visited.add(current); for (const child of children.get(current) || []) { level.set(child, Math.max(level.get(child) || 0, (level.get(current) || 0) + 1)); indegree.set(child, (indegree.get(child) || 0) - 1); if (indegree.get(child) === 0) queue.push(child); } }
  ids.filter((id) => !visited.has(id)).forEach((id, index) => level.set(id, index % 3)); return level;
}

function traceNodes(start: string, edges: Edge[]) { const parents = reachable(start, edges, 'up'); const children = reachable(start, edges, 'down'); return { parents, children, all: new Set([start, ...parents, ...children]) }; }
function reachable(start: string, edges: Edge[], direction: 'down' | 'up') { const seen = new Set<string>(); const queue = [start]; while (queue.length) { const current = queue.shift() as string; edges.filter((edge) => !edge.id.startsWith('identity:')).forEach((edge) => { const next = direction === 'down' ? edge.source === current ? edge.target : null : edge.target === current ? edge.source : null; if (next && !seen.has(next)) { seen.add(next); queue.push(next); } }); } seen.delete(start); return seen; }
function tableReference(nodeId: string, applications: LoadedApplication[]) { for (const application of applications) { const table = application.tables.find((candidate) => tableNodeId(application.id, candidate) === nodeId); if (table) return { application, table }; } return null; }
function flowBoardSize(nodes: ArchitectureFlowNode[], baseWidth: number, baseHeight: number) { const maxX = nodes.reduce((value, node) => Math.max(value, node.position.x + (node.measured?.width || TABLE_WIDTH)), baseWidth); const maxY = nodes.reduce((value, node) => Math.max(value, node.position.y + (node.measured?.height || 200)), baseHeight); return { width: Math.max(baseWidth, maxX + 180), height: Math.max(baseHeight, maxY + 180) }; }
function encodeHandle(prefix: 'in:' | 'out:', column: string) { return `${prefix}${encodeURIComponent(column)}`; }
function decodeHandle(handle: string, prefix: 'in:' | 'out:') { if (!handle.startsWith(prefix)) return ''; try { return decodeURIComponent(handle.slice(prefix.length)); } catch { return handle.slice(prefix.length); } }
function tableNodeId(applicationId: string, table: string) { return `table:${applicationId}:${normalize(table)}`; }
function shortTable(nodeId: string) { return nodeId.split(':').at(-1) || nodeId; }
function normalize(value?: string | null) { return String(value || '').trim().toLowerCase(); }
function relationshipLabel(edge: DiscoveryGraphEdge) { if (edge.pkColumn && edge.fkColumn) return edge.pkColumn === edge.fkColumn ? edge.fkColumn : `${edge.pkColumn} -> ${edge.fkColumn}`; return edge.fkColumn || edge.pkColumn || edge.label || undefined; }
