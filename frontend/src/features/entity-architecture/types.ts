import type { DiscoveryGraph } from '@/features/pii-discovery/types';

export type ApplicationSlice = {
  id: string;
  label: string;
  dataSourceId: number;
  dataSourceName: string;
  schema: string;
  tables: string[];
};

export type IdentityAnchor = {
  sliceId: string;
  table: string;
  column: string;
};

export type CrossDatabaseLinkKind = 'PARENT_CHILD' | 'SAME_AS' | 'REFERENCE';

export type CrossDatabaseLink = {
  id: string;
  parentSliceId: string;
  parentTable: string;
  parentColumn: string;
  childSliceId: string;
  childTable: string;
  childColumn: string;
  kind: CrossDatabaseLinkKind;
};

export type ArchitectureModelStatus = 'DRAFT' | 'CREATED';

export type ArchitectureFieldRule = {
  id: string;
  sliceId: string;
  table: string;
  column: string;
  dataType?: string | null;
  classificationId?: number | null;
  piiType?: string | null;
  piiStatus?: string | null;
  maskFunction?: string | null;
  maskParam1?: string | null;
  maskParam2?: string | null;
  generator?: string | null;
  generatorParam1?: string | null;
  generatorParam2?: string | null;
  confidence?: number | null;
  recommendationSource?: 'DISCOVERY' | 'PROFILE' | 'RELATIONSHIP' | 'MANUAL' | null;
  dependencyGroup?: string | null;
  dependencyRole?: 'PARENT' | 'CHILD' | 'PEER' | null;
  updatedAt?: string | null;
};

export type LoadedApplication = ApplicationSlice & {
  graph?: DiscoveryGraph;
  graphLoading?: boolean;
  graphError?: string | null;
};

export type BusinessEntityMemberRequest = {
  systemName: string;
  dataSourceId: number;
  schemaName: string;
  logicalRole: string;
  tableName: string;
  keyColumns?: string | null;
  joinToRole?: string | null;
  relationshipJson?: string | null;
  fieldRulesJson?: string | null;
  includeInSubset: boolean;
  includeInSynthetic: boolean;
  ordinalNo: number;
};

export type CreatedArchitecture = {
  entity?: {
    id?: number;
    name?: string;
  };
};
