export interface TdRecipe {
  id: number;
  recipeKey: string;
  name: string;
  description: string;
  assetType: string;
  attributesJson: string;
  anchor: boolean;
}

export interface TdPlanAsset {
  recipeKey: string;
  name: string;
  quantity: number;
  attributes: Record<string, string>;
  linkedTo: string | null;
}

export interface TdSafety {
  dataOrigin: string;
  maskingApplied: boolean;
  approvalRequired: boolean;
}

export interface TdPlan {
  summary: string;
  environment: string;
  quantity: number;
  assets: TdPlanAsset[];
  safety: TdSafety;
  openQuestions: string[];
  estimatedSeconds: number;
}

export interface TdProvisioned {
  type: string;
  id: string;
  label: string;
  attributes: Record<string, string>;
  linkedTo: string | null;
}

export interface TdReceipt {
  requestId: number;
  status: string;
  summary: string;
  environment: string;
  provisioned: TdProvisioned[];
  howToAccess: { environment: string; find: string; note: string };
  governance: Record<string, unknown>;
  auditRef: string;
  createdBy: string;
}

export interface TdRequestView {
  id: number;
  requestText: string;
  environment: string;
  purpose: string | null;
  quantity: number;
  status: string; // PLANNED | READY | FAILED | TORN_DOWN
  error: string | null;
  createdAt: string;
  summary: string | null;
  plan?: TdPlan | null;
  receipt?: TdReceipt | null;
}
