export type TopologySummary = {
  id: number;
  name: string;
  domain?: string | null;
  description?: string | null;
  status: string;
  currentHash?: string | null;
  currentVersion: number;
  lockVersion: number;
  sourceCount: number;
  nodeCount: number;
  edgeCount: number;
  ownerUsername?: string | null;
  visibility: string;
  createdAt: string;
  updatedAt: string;
};

export type SourceBinding = {
  id: number;
  topologyId: number;
  dataSourceId: number;
  dataSourceName: string;
  engine: string;
  schemaName: string;
  applicationLabel?: string | null;
  providerMode?: string | null;
  capturedAt?: string | null;
  nodeCount: number;
  edgeCount: number;
};

export type DataSourceOption = {
  id: number;
  name: string;
  kind: string;
  role: string;
  environment?: string | null;
};

export type SchemaOption = {
  schema: string;
  current?: boolean;
};

export type DiscoveryOperation = {
  id: number;
  topologyId: number;
  status: 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
  percent: number;
  completedSources: number;
  totalSources: number;
  completedObjects: number;
  totalObjects: number;
  currentSource?: string | null;
  currentSchema?: string | null;
  currentObject?: string | null;
  message?: string | null;
  errorMessage?: string | null;
  cancelRequested: boolean;
  requestedBy?: string | null;
  startedAt?: string | null;
  finishedAt?: string | null;
  createdAt: string;
};

export type GraphNode = {
  id: number;
  sourceBindingId: number;
  application: string;
  schema: string;
  name: string;
  objectType: string;
  columnCount: number;
  primaryKeyCount: number;
  rowEstimate?: number | null;
};

export type GraphEdge = {
  id: number;
  constraintName?: string | null;
  childNodeId: number;
  parentNodeId: number;
  childColumns: string[];
  parentColumns: string[];
  evidenceType: string;
  decisionStatus: string;
  confidence: number;
  enabled: boolean;
  evidenceJson?: string | null;
};

export type GraphSnapshot = {
  nodes: GraphNode[];
  edges: GraphEdge[];
  totalNodes: number;
  totalEdges: number;
  truncated: boolean;
};

export type ColumnSnapshot = {
  id: number;
  ordinal: number;
  name: string;
  dataType?: string | null;
  jdbcType: number;
  length?: number | null;
  scale?: number | null;
  nullable: boolean;
  primaryKey: boolean;
  uniqueKey: boolean;
  generated: boolean;
  defaultExpression?: string | null;
};

export type TopologyVersion = {
  id: number;
  versionNumber: number;
  contentHash: string;
  nodeCount: number;
  edgeCount: number;
  summaryJson?: string | null;
  createdBy?: string | null;
  createdAt: string;
};
