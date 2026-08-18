export type JsonMap = Record<string, unknown>;

export type DomainSummary = {
  id: number;
  topologyId: number;
  topologyVersion: number;
  topologyHash: string;
  name: string;
  businessDomain?: string | null;
  description?: string | null;
  status: string;
  versionNo: number;
  blueprintCount: number;
  assetCount: number;
  missionCount: number;
  ownerUsername?: string | null;
  visibility: string;
  createdAt: string;
  updatedAt: string;
};

export type DomainAsset = {
  id: number;
  domainId: number;
  assetType: string;
  artifactId: string;
  artifactVersion?: number | null;
  assetRole: string;
  required: boolean;
  config: JsonMap;
  createdAt: string;
};

export type RelationshipStatement = {
  edgeId: number;
  child: string;
  parent: string;
  childColumns: string[];
  parentColumns: string[];
  evidenceType: string;
  decisionStatus: string;
  statement: string;
};

export type Blueprint = {
  id: number;
  domainId: number;
  domainName: string;
  name: string;
  description?: string | null;
  entityType: string;
  status: string;
  versionNo: number;
  preconditions: unknown[];
  event: JsonMap;
  expected: unknown[];
  coverage: JsonMap;
  delivery: JsonMap;
  questionnaire: QuestionnaireField[];
  verification: JsonMap;
  ownerUsername?: string | null;
  createdAt: string;
  updatedAt: string;
};

export type QuestionnaireField = {
  key: string;
  label?: string;
  type?: string;
  required?: boolean;
  options?: unknown[];
  defaultValue?: unknown;
};

export type DomainDetail = {
  summary: DomainSummary;
  assets: DomainAsset[];
  blueprints: Blueprint[];
  relationships: RelationshipStatement[];
  settings: JsonMap;
  graphTruncated: boolean;
};

export type MissionCase = {
  id: number;
  missionId: string;
  ordinal: number;
  caseKey: string;
  title: string;
  caseKind: string;
  inputs: JsonMap;
  expected: unknown;
  status: string;
  evidence: JsonMap;
};

export type MissionEvent = {
  id: number;
  eventType: string;
  actor: string;
  message?: string | null;
  detail: JsonMap;
  createdAt: string;
};

export type Mission = {
  id: string;
  domainId: number;
  domainName: string;
  blueprintId: number;
  blueprintName: string;
  blueprintVersion: number;
  title: string;
  intent: string;
  targetEnvironment?: string | null;
  sourceStrategy: string;
  requestedCount: number;
  parameters: JsonMap;
  reservationRequested: boolean;
  reservationHours?: number | null;
  status: string;
  plan: JsonMap;
  coverage: JsonMap;
  verification: JsonMap;
  readyPack: JsonMap;
  selfServiceOrderId?: string | null;
  requestedBy?: string | null;
  createdAt: string;
  launchedAt?: string | null;
  completedAt?: string | null;
  updatedAt: string;
  blueprint: Blueprint;
  cases: MissionCase[];
  events: MissionEvent[];
};

export type SelfServiceProduct = {
  id: string;
  label: string;
  productType: string;
  description?: string | null;
  category?: string | null;
  status?: string | null;
};

export type MissionDraft = {
  blueprintId: number | null;
  title: string;
  intent: string;
  targetEnvironment: string;
  sourceStrategy: string;
  requestedCount: number;
  parameters: JsonMap;
  reservationRequested: boolean;
  reservationHours: number;
};

