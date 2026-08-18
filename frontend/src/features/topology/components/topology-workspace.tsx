'use client';

import '@xyflow/react/dist/style.css';

import {
  ActionIcon,
  Alert,
  Badge,
  Button,
  Drawer,
  Group,
  Loader,
  Progress,
  ScrollArea,
  Select,
  Stack,
  Table,
  Text,
  TextInput,
  Tooltip
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import {
  IconAlertTriangle,
  IconArrowLeft,
  IconCheck,
  IconDatabase,
  IconHistory,
  IconLink,
  IconPlayerStop,
  IconPlus,
  IconRefresh,
  IconSearch,
  IconTopologyComplex,
  IconX
} from '@tabler/icons-react';
import {
  Background,
  Controls,
  Handle,
  MarkerType,
  MiniMap,
  Position,
  ReactFlow,
  type Edge,
  type Node,
  type NodeProps
} from '@xyflow/react';
import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';

import { keys } from '@/lib/keys';
import {
  useDataSourceOptions,
  useLatestTopologyDiscovery,
  useNodeColumns,
  useSchemaOptions,
  useTopology,
  useTopologyActions,
  useTopologyGraph,
  useTopologySources,
  useTopologyVersions
} from '../hooks';
import type { GraphEdge, GraphNode, SourceBinding, TopologySummary } from '../types';

type WorkspaceStep = 'sources' | 'discover' | 'relationships' | 'versions';
type FlowNodeData = { label: string; application: string; columns: number; keys: number };
type TopologyFlowNode = Node<FlowNodeData, 'topologyNode'>;

const nodeTypes = { topologyNode: TopologyNode };

export function TopologyWorkspace({
  initial,
  onClose
}: {
  initial: TopologySummary;
  onClose: () => void;
}) {
  const [step, setStep] = useState<WorkspaceStep>(initial.currentVersion > 0 ? 'relationships' : 'sources');
  const topologyQuery = useTopology(initial.id);
  const topology = topologyQuery.data || initial;
  const sourcesQuery = useTopologySources(initial.id);
  const operationQuery = useLatestTopologyDiscovery(initial.id);
  const queryClient = useQueryClient();

  useEffect(() => {
    if (operationQuery.data?.status !== 'COMPLETED') return;
    queryClient.invalidateQueries({ queryKey: keys.topology.detail(initial.id) });
    queryClient.invalidateQueries({ queryKey: keys.topology.sources(initial.id) });
    queryClient.invalidateQueries({ queryKey: keys.topology.versions(initial.id) });
    queryClient.invalidateQueries({ queryKey: ['topology', initial.id, 'graph'] });
  }, [initial.id, operationQuery.data?.status, queryClient]);

  const steps: { value: WorkspaceStep; label: string; caption: string; complete: boolean }[] = [
    {
      value: 'sources',
      label: 'Sources',
      caption: `${sourcesQuery.data?.length || 0} attached`,
      complete: Boolean(sourcesQuery.data?.length)
    },
    {
      value: 'discover',
      label: 'Discovery',
      caption: operationQuery.data?.status || 'Not run',
      complete: operationQuery.data?.status === 'COMPLETED'
    },
    {
      value: 'relationships',
      label: 'Relationships',
      caption: `${topology.edgeCount} verified`,
      complete: topology.currentVersion > 0
    },
    {
      value: 'versions',
      label: 'Versions',
      caption: topology.currentVersion ? `Current v${topology.currentVersion}` : 'No version',
      complete: topology.currentVersion > 0
    }
  ];

  return (
    <section className="topology-workspace" aria-label={`${topology.name} topology workspace`}>
      <header className="topology-workspace-head">
        <Group gap="sm" wrap="nowrap">
          <Tooltip label="Return to topology catalog">
            <ActionIcon variant="subtle" color="gray" size="lg" onClick={onClose} aria-label="Close workspace">
              <IconArrowLeft size={20} />
            </ActionIcon>
          </Tooltip>
          <span className="topology-workspace-mark">
            <IconTopologyComplex size={20} />
          </span>
          <div className="topology-workspace-title">
            <Group gap="xs">
              <Text fw={800}>{topology.name}</Text>
              <Badge size="sm" variant="light" color={topology.status === 'ACTIVE' ? 'green' : 'gray'}>
                {topology.status}
              </Badge>
            </Group>
            <Text size="xs" c="dimmed" truncate>
              {topology.domain || 'Unassigned domain'} · topology intent only · no data movement
            </Text>
          </div>
        </Group>
        <Group gap="lg" className="topology-workspace-summary" wrap="nowrap">
          <WorkspaceMetric label="Sources" value={topology.sourceCount} />
          <WorkspaceMetric label="Objects" value={topology.nodeCount} />
          <WorkspaceMetric label="Relationships" value={topology.edgeCount} />
          <WorkspaceMetric label="Version" value={topology.currentVersion ? `v${topology.currentVersion}` : 'Draft'} />
          <ActionIcon variant="subtle" color="gray" size="lg" onClick={onClose} aria-label="Close workspace">
            <IconX size={20} />
          </ActionIcon>
        </Group>
      </header>

      <nav className="topology-journey" aria-label="Topology workflow">
        {steps.map((item, index) => (
          <button
            type="button"
            key={item.value}
            className={`topology-journey-step ${step === item.value ? 'is-active' : ''} ${item.complete ? 'is-complete' : ''}`}
            onClick={() => setStep(item.value)}
          >
            <span className="topology-journey-index">
              {item.complete ? <IconCheck size={13} /> : index + 1}
            </span>
            <span>
              <strong>{item.label}</strong>
              <small>{item.caption}</small>
            </span>
          </button>
        ))}
      </nav>

      <main className="topology-workspace-body">
        {step === 'sources' ? (
          <SourcesStep topology={topology} sources={sourcesQuery.data || []} loading={sourcesQuery.isPending} />
        ) : null}
        {step === 'discover' ? (
          <DiscoveryStep topology={topology} sourceCount={sourcesQuery.data?.length || 0} />
        ) : null}
        {step === 'relationships' ? (
          <RelationshipsStep topology={topology} sources={sourcesQuery.data || []} />
        ) : null}
        {step === 'versions' ? <VersionsStep topology={topology} /> : null}
      </main>
    </section>
  );
}

function SourcesStep({
  topology,
  sources,
  loading
}: {
  topology: TopologySummary;
  sources: SourceBinding[];
  loading: boolean;
}) {
  const [drawerOpen, setDrawerOpen] = useState(false);
  const actions = useTopologyActions();

  const detach = async (binding: SourceBinding) => {
    if (!window.confirm(`Detach ${binding.dataSourceName} / ${binding.schemaName} from this topology?`)) return;
    try {
      await actions.detachSource.mutateAsync({ id: topology.id, bindingId: binding.id });
      notifications.show({ color: 'green', message: 'Source detached' });
    } catch (error) {
      notifications.show({
        color: 'red',
        title: 'Could not detach source',
        message: error instanceof Error ? error.message : 'Request failed'
      });
    }
  };

  return (
    <>
      <section className="topology-step-shell">
        <div className="topology-step-head">
          <div>
            <Text fw={750}>Attached application schemas</Text>
            <Text size="sm" c="dimmed">
              Add only systems the topology needs. Access is rechecked whenever the graph is opened or refreshed.
            </Text>
          </div>
          <Button leftSection={<IconPlus size={16} />} onClick={() => setDrawerOpen(true)}>
            Attach source
          </Button>
        </div>
        {loading ? (
          <Loader size="sm" />
        ) : sources.length === 0 ? (
          <div className="topology-empty">
            <IconDatabase size={28} />
            <Text fw={700}>No sources attached</Text>
            <Text size="sm" c="dimmed">
              Attach a source-capable connection and one schema to begin.
            </Text>
            <Button variant="light" leftSection={<IconPlus size={16} />} onClick={() => setDrawerOpen(true)}>
              Attach first source
            </Button>
          </div>
        ) : (
          <div className="topology-source-list">
            <div className="topology-source-head" aria-hidden="true">
              <span>Application</span>
              <span>Physical source</span>
              <span>Capture</span>
              <span>Coverage</span>
              <span />
            </div>
            {sources.map((source) => (
              <div className="topology-source-row" key={source.id}>
                <div>
                  <Text fw={700}>{source.applicationLabel || source.dataSourceName}</Text>
                  <Text size="xs" c="dimmed">
                    Application label
                  </Text>
                </div>
                <div>
                  <Text size="sm" fw={650}>
                    {source.dataSourceName}
                  </Text>
                  <Text size="xs" c="dimmed">
                    {source.engine} · {source.schemaName}
                  </Text>
                </div>
                <div>
                  <Badge size="sm" variant="light" color={source.capturedAt ? 'green' : 'gray'}>
                    {source.providerMode || 'Not captured'}
                  </Badge>
                  <Text size="xs" c="dimmed" mt={3}>
                    {source.capturedAt ? new Date(source.capturedAt).toLocaleString() : 'Awaiting discovery'}
                  </Text>
                </div>
                <div>
                  <Text size="sm" fw={650}>
                    {source.nodeCount} objects · {source.edgeCount} links
                  </Text>
                  <Text size="xs" c="dimmed">
                    Last good snapshot
                  </Text>
                </div>
                <Button variant="subtle" color="red" size="xs" onClick={() => detach(source)}>
                  Detach
                </Button>
              </div>
            ))}
          </div>
        )}
      </section>
      <AttachSourceDrawer
        topologyId={topology.id}
        opened={drawerOpen}
        onClose={() => setDrawerOpen(false)}
      />
    </>
  );
}

function AttachSourceDrawer({
  topologyId,
  opened,
  onClose
}: {
  topologyId: number;
  opened: boolean;
  onClose: () => void;
}) {
  const sourcesQuery = useDataSourceOptions();
  const [dataSourceId, setDataSourceId] = useState<number | null>(null);
  const [schema, setSchema] = useState<string | null>(null);
  const [label, setLabel] = useState('');
  const schemasQuery = useSchemaOptions(dataSourceId);
  const actions = useTopologyActions();

  useEffect(() => {
    if (!opened) return;
    setDataSourceId(null);
    setSchema(null);
    setLabel('');
  }, [opened]);

  const sourceOptions = (sourcesQuery.data || [])
    .filter((source) => source.role === 'SOURCE' || source.role === 'BOTH')
    .map((source) => ({
      value: String(source.id),
      label: `${source.name} · ${source.kind}${source.environment ? ` · ${source.environment}` : ''}`
    }));
  const schemaOptions = (schemasQuery.data || []).map((item) => ({
    value: item.schema,
    label: item.current ? `${item.schema} · current` : item.schema
  }));

  const attach = async () => {
    if (!dataSourceId || !schema) return;
    try {
      await actions.attachSource.mutateAsync({
        id: topologyId,
        dataSourceId,
        schemaName: schema,
        applicationLabel: label.trim() || undefined
      });
      notifications.show({ color: 'green', title: 'Source attached', message: 'Ready for topology discovery.' });
      onClose();
    } catch (error) {
      notifications.show({
        color: 'red',
        title: 'Could not attach source',
        message: error instanceof Error ? error.message : 'Request failed'
      });
    }
  };

  return (
    <Drawer opened={opened} onClose={onClose} position="right" size={470} title="Attach application schema">
      <Stack gap="lg">
        <div>
          <Text fw={700}>Choose a governed source</Text>
          <Text size="sm" c="dimmed">
            Only source-capable connections visible to your account are available.
          </Text>
        </div>
        <Select
          label="Data source"
          placeholder="Search connections"
          searchable
          data={sourceOptions}
          value={dataSourceId ? String(dataSourceId) : null}
          onChange={(value) => {
            setDataSourceId(value ? Number(value) : null);
            setSchema(null);
          }}
          nothingFoundMessage="No source-capable connections"
        />
        <Select
          label="Schema"
          placeholder={dataSourceId ? 'Search schemas' : 'Choose a data source first'}
          searchable
          disabled={!dataSourceId}
          rightSection={schemasQuery.isFetching ? <Loader size={15} /> : undefined}
          data={schemaOptions}
          value={schema}
          onChange={setSchema}
          nothingFoundMessage="No visible schemas"
        />
        <TextInput
          label="Application label"
          description="Optional friendly name shown on the relationship map."
          placeholder="Core banking, Card servicing"
          value={label}
          maxLength={120}
          onChange={(event) => setLabel(event.currentTarget.value)}
        />
        <Group justify="flex-end">
          <Button variant="subtle" color="gray" onClick={onClose}>
            Cancel
          </Button>
          <Button onClick={attach} disabled={!dataSourceId || !schema} loading={actions.attachSource.isPending}>
            Attach source
          </Button>
        </Group>
      </Stack>
    </Drawer>
  );
}

function DiscoveryStep({ topology, sourceCount }: { topology: TopologySummary; sourceCount: number }) {
  const operationQuery = useLatestTopologyDiscovery(topology.id);
  const operation = operationQuery.data;
  const actions = useTopologyActions();
  const active = operation?.status === 'QUEUED' || operation?.status === 'RUNNING';

  const discover = async () => {
    try {
      await actions.discover.mutateAsync(topology.id);
      notifications.show({ color: 'blue', title: 'Discovery started', message: 'Live progress is now available.' });
    } catch (error) {
      notifications.show({
        color: 'red',
        title: 'Could not start discovery',
        message: error instanceof Error ? error.message : 'Request failed'
      });
    }
  };
  const cancel = async () => {
    if (!operation) return;
    await actions.cancelDiscovery.mutateAsync({ id: topology.id, operationId: operation.id });
  };

  return (
    <section className="topology-step-shell topology-discovery-step">
      <div className="topology-step-head">
        <div>
          <Text fw={750}>Metadata discovery</Text>
          <Text size="sm" c="dimmed">
            Captures tables, views, columns, keys, and declared relationships without reading business rows.
          </Text>
        </div>
        <Group gap="xs">
          {active ? (
            <Button
              variant="light"
              color="red"
              leftSection={<IconPlayerStop size={16} />}
              onClick={cancel}
              loading={actions.cancelDiscovery.isPending}
            >
              Cancel
            </Button>
          ) : null}
          <Button
            leftSection={<IconRefresh size={16} />}
            onClick={discover}
            disabled={sourceCount === 0 || active}
            loading={actions.discover.isPending}
          >
            {topology.currentVersion ? 'Refresh topology' : 'Discover topology'}
          </Button>
        </Group>
      </div>
      {!operation ? (
        <div className="topology-empty">
          <IconTopologyComplex size={30} />
          <Text fw={700}>No discovery run yet</Text>
          <Text size="sm" c="dimmed">
            {sourceCount ? 'Start discovery to create the first governed graph.' : 'Attach at least one source first.'}
          </Text>
        </div>
      ) : (
        <div className="topology-operation">
          <div className="topology-operation-state">
            <div>
              <Group gap="xs">
                <Badge color={statusColor(operation.status)} variant="light">
                  {operation.status}
                </Badge>
                <Text fw={750}>{operation.message || 'Discovery operation'}</Text>
              </Group>
              <Text size="sm" c="dimmed" mt={4}>
                {operation.currentSource
                  ? `${operation.currentSource} / ${operation.currentSchema || 'default schema'}`
                  : `Requested by ${operation.requestedBy || 'system'}`}
              </Text>
            </div>
            <Text className="topology-operation-percent">{operation.percent}%</Text>
          </div>
          <Progress value={operation.percent} size="md" radius="xl" animated={active} color={statusColor(operation.status)} />
          <div className="topology-operation-metrics">
            <OperationMetric label="Sources" value={`${operation.completedSources} / ${operation.totalSources}`} />
            <OperationMetric label="Objects" value={`${operation.completedObjects} / ${operation.totalObjects}`} />
            <OperationMetric label="Current object" value={operation.currentObject || 'None'} />
            <OperationMetric
              label="Started"
              value={operation.startedAt ? new Date(operation.startedAt).toLocaleTimeString() : 'Queued'}
            />
          </div>
          {operation.errorMessage ? (
            <Alert color="red" icon={<IconAlertTriangle size={18} />} title="Discovery failed">
              {operation.errorMessage}
            </Alert>
          ) : null}
          <Text size="xs" c="dimmed">
            The last good topology remains active until this operation completes successfully.
          </Text>
        </div>
      )}
    </section>
  );
}

function RelationshipsStep({ topology, sources }: { topology: TopologySummary; sources: SourceBinding[] }) {
  const [search, setSearch] = useState('');
  const [sourceId, setSourceId] = useState<number | null>(null);
  const [selectedNode, setSelectedNode] = useState<GraphNode | null>(null);
  const [selectedEdge, setSelectedEdge] = useState<GraphEdge | null>(null);
  const graphQuery = useTopologyGraph(topology.id, search, sourceId);
  const graph = graphQuery.data;

  const flow = useMemo(() => layoutGraph(graph?.nodes || [], graph?.edges || []), [graph]);
  const nodeById = useMemo(
    () => new Map((graph?.nodes || []).map((node) => [node.id, node])),
    [graph?.nodes]
  );

  if (!topology.currentVersion) {
    return (
      <section className="topology-step-shell">
        <div className="topology-empty">
          <IconLink size={28} />
          <Text fw={700}>No active graph</Text>
          <Text size="sm" c="dimmed">
            Complete discovery before reviewing relationships.
          </Text>
        </div>
      </section>
    );
  }

  return (
    <section className="topology-graph-step">
      <div className="topology-graph-toolbar">
        <div>
          <Text fw={750}>Relationship map</Text>
          <Text size="xs" c="dimmed">
            Declared database relationships are verified evidence. Inference is deliberately off in this release.
          </Text>
        </div>
        <Group gap="xs" wrap="nowrap">
          <TextInput
            leftSection={<IconSearch size={15} />}
            placeholder="Find schema or table"
            value={search}
            onChange={(event) => setSearch(event.currentTarget.value)}
            w={250}
          />
          <Select
            placeholder="All applications"
            clearable
            value={sourceId ? String(sourceId) : null}
            onChange={(value) => setSourceId(value ? Number(value) : null)}
            data={sources.map((source) => ({
              value: String(source.id),
              label: source.applicationLabel || source.dataSourceName
            }))}
            w={210}
          />
          <Badge variant="light">{graph?.totalNodes || 0} objects</Badge>
          <Badge variant="light">{graph?.totalEdges || 0} links</Badge>
        </Group>
      </div>
      {graphQuery.isPending ? (
        <div className="topology-graph-loading">
          <Loader size="sm" />
          <Text size="sm" c="dimmed">
            Loading graph
          </Text>
        </div>
      ) : (
        <div className="topology-flow">
          <ReactFlow
            nodes={flow.nodes}
            edges={flow.edges}
            nodeTypes={nodeTypes}
            fitView
            minZoom={0.15}
            maxZoom={1.6}
            nodesDraggable
            nodesConnectable={false}
            elementsSelectable
            onNodeClick={(_event, node) => {
              setSelectedEdge(null);
              setSelectedNode(nodeById.get(Number(node.id)) || null);
            }}
            onEdgeClick={(_event, edge) => {
              setSelectedNode(null);
              setSelectedEdge((graph?.edges || []).find((item) => String(item.id) === edge.id) || null);
            }}
          >
            <Background gap={18} size={1} />
            <MiniMap pannable zoomable nodeStrokeWidth={2} />
            <Controls showInteractive={false} />
          </ReactFlow>
          {graph?.truncated ? (
            <div className="topology-graph-notice">
              Showing 300 of {graph.totalNodes} objects. Search or filter an application to focus the map.
            </div>
          ) : null}
        </div>
      )}
      <GraphDetailDrawer
        topologyId={topology.id}
        node={selectedNode}
        edge={selectedEdge}
        nodeById={nodeById}
        onClose={() => {
          setSelectedNode(null);
          setSelectedEdge(null);
        }}
      />
    </section>
  );
}

function GraphDetailDrawer({
  topologyId,
  node,
  edge,
  nodeById,
  onClose
}: {
  topologyId: number;
  node: GraphNode | null;
  edge: GraphEdge | null;
  nodeById: Map<number, GraphNode>;
  onClose: () => void;
}) {
  const columnsQuery = useNodeColumns(topologyId, node?.id || null);
  const actions = useTopologyActions();
  const opened = Boolean(node || edge);
  const child = edge ? nodeById.get(edge.childNodeId) : null;
  const parent = edge ? nodeById.get(edge.parentNodeId) : null;

  const decide = async (status: string) => {
    if (!edge) return;
    try {
      await actions.reviewEdge.mutateAsync({ id: topologyId, edgeId: edge.id, status });
      notifications.show({ color: 'green', message: `Relationship marked ${status.toLowerCase()}` });
      onClose();
    } catch (error) {
      notifications.show({
        color: 'red',
        title: 'Could not update relationship',
        message: error instanceof Error ? error.message : 'Request failed'
      });
    }
  };

  return (
    <Drawer
      opened={opened}
      onClose={onClose}
      position="right"
      size={520}
      title={node ? `${node.schema}.${node.name}` : edge?.constraintName || 'Relationship evidence'}
    >
      {node ? (
        <Stack gap="md">
          <Group gap="xs">
            <Badge variant="light">{node.application}</Badge>
            <Badge variant="light" color="gray">
              {node.objectType}
            </Badge>
            <Badge variant="light" color={node.primaryKeyCount ? 'green' : 'yellow'}>
              {node.primaryKeyCount ? `${node.primaryKeyCount} PK column(s)` : 'No primary key'}
            </Badge>
          </Group>
          <Table striped highlightOnHover withTableBorder>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Column</Table.Th>
                <Table.Th>Type</Table.Th>
                <Table.Th>Constraint</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {(columnsQuery.data || []).map((column) => (
                <Table.Tr key={column.id}>
                  <Table.Td>{column.name}</Table.Td>
                  <Table.Td>
                    {column.dataType}
                    {column.length ? `(${column.length}${column.scale != null ? `,${column.scale}` : ''})` : ''}
                  </Table.Td>
                  <Table.Td>
                    {column.primaryKey ? 'PK' : column.uniqueKey ? 'Unique' : column.nullable ? 'Nullable' : 'Required'}
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Stack>
      ) : null}
      {edge ? (
        <Stack gap="lg">
          <div>
            <Text size="xs" fw={750} c="dimmed" tt="uppercase">
              Child
            </Text>
            <Text fw={700}>
              {child?.schema}.{child?.name}
            </Text>
            <Text size="sm" c="dimmed">
              {edge.childColumns.join(', ')}
            </Text>
          </div>
          <div>
            <Text size="xs" fw={750} c="dimmed" tt="uppercase">
              Parent
            </Text>
            <Text fw={700}>
              {parent?.schema}.{parent?.name}
            </Text>
            <Text size="sm" c="dimmed">
              {edge.parentColumns.join(', ')}
            </Text>
          </div>
          <div className="topology-evidence">
            <Group justify="space-between">
              <Text fw={700}>Why this link exists</Text>
              <Badge color="green">{edge.confidence}% confidence</Badge>
            </Group>
            <Text size="sm" mt="xs">
              The source database declares this foreign-key constraint. No name-based inference was used.
            </Text>
            <Text size="xs" c="dimmed" mt="xs">
              Evidence: {edge.evidenceType} · Decision: {edge.decisionStatus}
            </Text>
          </div>
          <Group>
            <Button onClick={() => decide('VERIFIED')} leftSection={<IconCheck size={16} />}>
              Verify
            </Button>
            <Button variant="light" color="yellow" onClick={() => decide('DISABLED')}>
              Disable
            </Button>
            <Button variant="subtle" color="red" onClick={() => decide('REJECTED')}>
              Reject
            </Button>
          </Group>
        </Stack>
      ) : null}
    </Drawer>
  );
}

function VersionsStep({ topology }: { topology: TopologySummary }) {
  const versionsQuery = useTopologyVersions(topology.id);
  return (
    <section className="topology-step-shell">
      <div className="topology-step-head">
        <div>
          <Text fw={750}>Immutable capture versions</Text>
          <Text size="sm" c="dimmed">
            Hashes include stable objects, columns, constraints, and review decisions. Timestamps and row estimates are excluded.
          </Text>
        </div>
        <IconHistory size={22} />
      </div>
      <div className="topology-version-list">
        {(versionsQuery.data || []).map((version) => (
          <div className="topology-version-row" key={version.id}>
            <span className="topology-version-number">v{version.versionNumber}</span>
            <div>
              <Text fw={700}>{version.nodeCount} objects · {version.edgeCount} relationships</Text>
              <Text size="xs" c="dimmed">
                {version.contentHash}
              </Text>
            </div>
            <div>
              <Text size="sm">{new Date(version.createdAt).toLocaleString()}</Text>
              <Text size="xs" c="dimmed">
                {version.createdBy || 'system'}
              </Text>
            </div>
            {version.versionNumber === topology.currentVersion ? <Badge color="green">Current</Badge> : <Badge color="gray">Retained</Badge>}
          </div>
        ))}
        {!versionsQuery.isPending && !versionsQuery.data?.length ? (
          <div className="topology-empty">
            <IconHistory size={28} />
            <Text fw={700}>No versions yet</Text>
            <Text size="sm" c="dimmed">
              A version is created after every successful discovery.
            </Text>
          </div>
        ) : null}
      </div>
    </section>
  );
}

function TopologyNode({ data }: NodeProps<TopologyFlowNode>) {
  return (
    <div className="topology-flow-node">
      <Handle type="target" position={Position.Left} />
      <div className="topology-flow-node-app">{data.application}</div>
      <div className="topology-flow-node-name">{data.label}</div>
      <div className="topology-flow-node-meta">
        {data.columns} columns · {data.keys ? `${data.keys} PK` : 'no PK'}
      </div>
      <Handle type="source" position={Position.Right} />
    </div>
  );
}

function layoutGraph(nodes: GraphNode[], edges: GraphEdge[]) {
  const groups = new Map<string, GraphNode[]>();
  nodes.forEach((node) => {
    const group = groups.get(node.application) || [];
    group.push(node);
    groups.set(node.application, group);
  });
  const flowNodes: TopologyFlowNode[] = [];
  [...groups.entries()].forEach(([, group], groupIndex) => {
    group.forEach((node, rowIndex) => {
      flowNodes.push({
        id: String(node.id),
        type: 'topologyNode',
        position: {
          x: groupIndex * 310 + (rowIndex % 2) * 22,
          y: Math.floor(rowIndex / 2) * 122 + (rowIndex % 2) * 52
        },
        data: {
          label: `${node.schema}.${node.name}`,
          application: node.application,
          columns: node.columnCount,
          keys: node.primaryKeyCount
        }
      });
    });
  });
  const flowEdges: Edge[] = edges.map((edge) => ({
    id: String(edge.id),
    source: String(edge.childNodeId),
    target: String(edge.parentNodeId),
    markerEnd: { type: MarkerType.ArrowClosed, width: 14, height: 14 },
    style: {
      stroke: edge.enabled ? 'var(--forge-blue)' : 'var(--forge-muted)',
      strokeWidth: edge.decisionStatus === 'VERIFIED' ? 1.6 : 1.1,
      strokeDasharray: edge.enabled ? undefined : '5 4',
      opacity: edge.enabled ? 0.72 : 0.45
    }
  }));
  return { nodes: flowNodes, edges: flowEdges };
}

function WorkspaceMetric({ label, value }: { label: string; value: string | number }) {
  return (
    <div className="topology-workspace-metric">
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

function OperationMetric({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <span>{label}</span>
      <strong title={value}>{value}</strong>
    </div>
  );
}

function statusColor(status: string) {
  if (status === 'COMPLETED') return 'green';
  if (status === 'FAILED') return 'red';
  if (status === 'CANCELLED') return 'gray';
  return 'blue';
}
