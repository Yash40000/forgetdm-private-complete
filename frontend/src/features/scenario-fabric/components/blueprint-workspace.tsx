'use client';

import {
  Badge,
  Button,
  Group,
  Modal,
  MultiSelect,
  ScrollArea,
  Select,
  SimpleGrid,
  Stack,
  Text,
  TextInput,
  Textarea
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconForms, IconPlus, IconX } from '@tabler/icons-react';
import { useEffect, useMemo, useState } from 'react';

import { usePermissions } from '@/lib/use-permissions';
import { useScenarioActions, useScenarioProducts } from '../hooks';
import type { Blueprint, DomainSummary } from '../types';
import { asMap, errorMessage, listOfText, textValue } from '../utils';

const TECHNIQUES = ['BASELINE', 'BOUNDARY', 'NEGATIVE', 'STATE_TRANSITION', 'PAIRWISE'];

type Draft = {
  id: number | null;
  domainId: number | null;
  name: string;
  description: string;
  entityType: string;
  event: string;
  preconditions: string;
  expected: string;
  techniques: string[];
  parameters: string;
  predicates: string;
  productId: string | null;
};

const EMPTY: Draft = {
  id: null,
  domainId: null,
  name: '',
  description: '',
  entityType: '',
  event: '',
  preconditions: 'entity.status=ACTIVE',
  expected: 'delivery.status=READY_TO_TEST',
  techniques: TECHNIQUES,
  parameters: 'customerTier=STANDARD|PREMIUM\nchannel=WEB|MOBILE|BRANCH',
  predicates: '',
  productId: null
};

export function BlueprintWorkspace({
  opened,
  domains,
  blueprints,
  initialId,
  onClose
}: {
  opened: boolean;
  domains: DomainSummary[];
  blueprints: Blueprint[];
  initialId?: number | null;
  onClose: () => void;
}) {
  const { can } = usePermissions();
  const canManage = can('scenario.manage');
  const products = useScenarioProducts();
  const actions = useScenarioActions();
  const [draft, setDraft] = useState<Draft>(EMPTY);

  const selected = useMemo(() => blueprints.find((item) => item.id === draft.id), [blueprints, draft.id]);

  useEffect(() => {
    if (!opened) return;
    const item = blueprints.find((blueprint) => blueprint.id === initialId) || blueprints[0];
    setDraft(item ? fromBlueprint(item) : { ...EMPTY, domainId: domains[0]?.id || null });
  }, [opened, initialId, blueprints, domains]);

  const choose = (item: Blueprint) => setDraft(fromBlueprint(item));
  const createNew = () => setDraft({ ...EMPTY, domainId: domains[0]?.id || null });

  const save = async () => {
    if (!draft.domainId) return;
    const parameters = parseOptions(draft.parameters);
    const body = {
      name: draft.name,
      description: draft.description,
      entityType: draft.entityType,
      status: 'PUBLISHED',
      preconditions: parseConditions(draft.preconditions),
      event: { action: draft.event || 'provision required test state', parameters: {} },
      expected: parseConditions(draft.expected),
      coverage: { techniques: draft.techniques, parameters },
      delivery: { defaultStrategy: 'AUTO', productId: draft.productId || undefined, systems: [] },
      questionnaire: Object.entries(parameters).map(([key, options]) => ({
        key,
        label: humanize(key),
        type: 'SELECT',
        required: true,
        options
      })),
      verification: {
        checks: ['ENGINE_COMPLETED', 'NO_REJECTS', 'TOPOLOGY_COMPATIBLE', 'COVERAGE_RETAINED'],
        predicates: parseConditions(draft.predicates)
      }
    };
    try {
      const result = draft.id
        ? await actions.updateBlueprint.mutateAsync({ id: draft.id, body })
        : await actions.createBlueprint.mutateAsync({ domainId: draft.domainId, body });
      setDraft(fromBlueprint(result));
      notifications.show({
        color: 'green',
        title: draft.id ? 'Blueprint version published' : 'Scenario Blueprint created',
        message: `${result.name} / version ${result.versionNo}`
      });
    } catch (error) {
      notifications.show({ color: 'red', title: 'Could not save Blueprint', message: errorMessage(error) });
    }
  };

  const valid = Boolean(
    canManage &&
    draft.domainId &&
    draft.name.trim().length >= 8 &&
    draft.entityType.trim() &&
    draft.techniques.length
  );

  return (
    <Modal opened={opened} onClose={onClose} fullScreen padding={0} title={null}>
      <div className="scenario-fullscreen">
        <header className="scenario-fullscreen-head">
          <Group gap="sm">
            <span className="scenario-page-mark"><IconForms size={20} /></span>
            <div><Text fw={850} size="lg">Scenario Blueprint Studio</Text><Text size="sm" c="dimmed">Author reusable test intent, coverage rules, and proof of readiness.</Text></div>
          </Group>
          <Group gap="xs">
            {canManage ? <Button variant="light" leftSection={<IconPlus size={16} />} onClick={createNew}>New Blueprint</Button> : null}
            <Button variant="subtle" color="gray" onClick={onClose} aria-label="Close"><IconX size={20} /></Button>
          </Group>
        </header>

        <div className="scenario-master-detail">
          <aside className="scenario-master-list">
            <Text size="xs" fw={800} c="dimmed" tt="uppercase">Blueprint library</Text>
            {blueprints.map((item) => (
              <button type="button" className={item.id === draft.id ? 'is-active' : ''} key={item.id} onClick={() => choose(item)}>
                <span><IconForms size={16} /></span>
                <div><strong>{item.name}</strong><small>{item.entityType} / v{item.versionNo}</small></div>
              </button>
            ))}
          </aside>

          <ScrollArea className="scenario-detail-pane">
            <div className="scenario-blueprint-editor">
              <div className="scenario-detail-title">
                <div>
                  <Group gap="xs"><Text fw={850} size="xl">{draft.id ? draft.name : 'New Scenario Blueprint'}</Text>{selected ? <Badge variant="light">Version {selected.versionNo}</Badge> : null}</Group>
                  <Text c="dimmed">Keep business intent readable; the compiler turns it into deterministic case coverage.</Text>
                </div>
                <Button loading={actions.createBlueprint.isPending || actions.updateBlueprint.isPending} disabled={!valid} onClick={save}>{draft.id ? 'Publish new version' : 'Create Blueprint'}</Button>
              </div>

              <section className="scenario-authoring-panel">
                <Text fw={800}>Identity and scope</Text>
                <SimpleGrid cols={{ base: 1, md: 3 }}>
                  <Select label="Test Domain" searchable disabled={Boolean(draft.id)} data={domains.map((domain) => ({ value: String(domain.id), label: domain.name }))} value={draft.domainId ? String(draft.domainId) : null} onChange={(value) => setDraft({ ...draft, domainId: value ? Number(value) : null })} />
                  <TextInput label="Blueprint name" minLength={8} maxLength={120} value={draft.name} onChange={(event) => setDraft({ ...draft, name: event.currentTarget.value })} />
                  <TextInput label="Business object / capability" value={draft.entityType} onChange={(event) => setDraft({ ...draft, entityType: event.currentTarget.value })} placeholder="Card payment, retail customer, loan" />
                </SimpleGrid>
                <Textarea label="Tester-facing purpose" minRows={2} maxLength={2000} value={draft.description} onChange={(event) => setDraft({ ...draft, description: event.currentTarget.value })} />
              </section>

              <SimpleGrid cols={{ base: 1, lg: 2 }}>
                <section className="scenario-authoring-panel">
                  <Text fw={800}>Required state</Text>
                  <Text size="xs" c="dimmed">One condition per line as field=value. These become baseline and negative cases.</Text>
                  <Textarea minRows={5} value={draft.preconditions} onChange={(event) => setDraft({ ...draft, preconditions: event.currentTarget.value })} />
                </section>
                <section className="scenario-authoring-panel">
                  <Text fw={800}>Event and expected state</Text>
                  <TextInput label="Business event" value={draft.event} onChange={(event) => setDraft({ ...draft, event: event.currentTarget.value })} placeholder="Authorize a card payment" />
                  <Textarea label="Expected outcomes" description="One field=value assertion per line" minRows={3} value={draft.expected} onChange={(event) => setDraft({ ...draft, expected: event.currentTarget.value })} />
                </section>
              </SimpleGrid>

              <section className="scenario-authoring-panel">
                <Text fw={800}>Coverage intelligence</Text>
                <MultiSelect label="Techniques" data={TECHNIQUES} value={draft.techniques} onChange={(value) => setDraft({ ...draft, techniques: value })} />
                <Textarea label="Variation domains" description="One line per choice: channel=WEB|MOBILE|BRANCH. Pairwise coverage is generated from these values." minRows={4} value={draft.parameters} onChange={(event) => setDraft({ ...draft, parameters: event.currentTarget.value })} />
              </section>

              <SimpleGrid cols={{ base: 1, lg: 2 }}>
                <section className="scenario-authoring-panel">
                  <Text fw={800}>Delivery contract</Text>
                  <Select label="Approved Self-Service product" searchable clearable data={(products.data || []).map((item) => ({ value: item.id, label: `${item.label} / ${item.productType}` }))} value={draft.productId} onChange={(value) => setDraft({ ...draft, productId: value })} placeholder="Use Test Domain default" />
                </section>
                <section className="scenario-authoring-panel">
                  <Text fw={800}>Target verification</Text>
                  <Textarea label="Semantic predicates" description="Optional field=value proof checks; technical checks always run." minRows={3} value={draft.predicates} onChange={(event) => setDraft({ ...draft, predicates: event.currentTarget.value })} />
                </section>
              </SimpleGrid>
            </div>
          </ScrollArea>
        </div>
      </div>
    </Modal>
  );
}

function parseConditions(value: string) {
  return value.split(/\r?\n/).map((line) => line.trim()).filter(Boolean).map((line) => {
    const [field, ...rest] = line.split('=');
    return { field: field.trim(), operator: 'EQUALS', value: rest.join('=').trim(), required: true };
  });
}

function parseOptions(value: string): Record<string, string[]> {
  const result: Record<string, string[]> = {};
  for (const line of value.split(/\r?\n/).map((item) => item.trim()).filter(Boolean)) {
    const [key, values = ''] = line.split('=');
    const options = values.split('|').map((item) => item.trim()).filter(Boolean);
    if (key?.trim() && options.length) result[key.trim()] = options;
  }
  return result;
}

function fromBlueprint(item: Blueprint): Draft {
  const delivery = asMap(item.delivery);
  const coverage = asMap(item.coverage);
  return {
    id: item.id,
    domainId: item.domainId,
    name: item.name,
    description: item.description || '',
    entityType: item.entityType,
    event: textValue(asMap(item.event).action),
    preconditions: conditionsToText(item.preconditions),
    expected: conditionsToText(item.expected),
    techniques: listOfText(coverage.techniques),
    parameters: optionsToText(asMap(coverage.parameters)),
    predicates: conditionsToText(Array.isArray(asMap(item.verification).predicates) ? asMap(item.verification).predicates as unknown[] : []),
    productId: textValue(delivery.productId) || null
  };
}

function conditionsToText(values: unknown[]) {
  return values.map((value) => {
    const row = asMap(value);
    return `${textValue(row.field)}=${textValue(row.value)}`;
  }).filter((line) => line !== '=').join('\n');
}

function optionsToText(values: Record<string, unknown>) {
  return Object.entries(values).map(([key, options]) => `${key}=${listOfText(options).join('|')}`).join('\n');
}

function humanize(value: string) {
  return value.replace(/([a-z])([A-Z])/g, '$1 $2').replaceAll('_', ' ').replace(/\b\w/g, (letter) => letter.toUpperCase());
}
