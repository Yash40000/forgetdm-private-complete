'use client';

import {
  Button,
  Divider,
  Drawer,
  Group,
  NumberInput,
  Select,
  SimpleGrid,
  Stack,
  Switch,
  Text,
  TextInput,
  Textarea
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconArrowRight, IconSparkles } from '@tabler/icons-react';
import { useEffect, useMemo, useState } from 'react';

import { useScenarioActions } from '../hooks';
import type { Blueprint, JsonMap, Mission, MissionDraft, QuestionnaireField } from '../types';
import { errorMessage, textValue } from '../utils';

const EMPTY: MissionDraft = {
  blueprintId: null,
  title: '',
  intent: '',
  targetEnvironment: 'QA',
  sourceStrategy: 'AUTO',
  requestedCount: 100,
  parameters: {},
  reservationRequested: false,
  reservationHours: 24
};

export function MissionRequestDrawer({
  opened,
  blueprints,
  initialBlueprintId,
  onClose,
  onCreated
}: {
  opened: boolean;
  blueprints: Blueprint[];
  initialBlueprintId?: number | null;
  onClose: () => void;
  onCreated: (mission: Mission) => void;
}) {
  const actions = useScenarioActions();
  const [draft, setDraft] = useState<MissionDraft>(EMPTY);

  useEffect(() => {
    if (!opened) return;
    const blueprintId = initialBlueprintId || blueprints[0]?.id || null;
    setDraft({ ...EMPTY, blueprintId, parameters: defaultsFor(blueprints.find((item) => item.id === blueprintId)) });
  }, [opened, initialBlueprintId, blueprints]);

  const selected = useMemo(
    () => blueprints.find((blueprint) => blueprint.id === draft.blueprintId),
    [blueprints, draft.blueprintId]
  );
  const missingQuestion = (selected?.questionnaire || []).some(
    (field) => field.required && !textValue(draft.parameters[field.key]).trim()
  );
  const valid =
    Boolean(draft.blueprintId) &&
    draft.title.trim().length >= 8 &&
    draft.intent.trim().length >= 20 &&
    Boolean(draft.targetEnvironment) &&
    draft.requestedCount >= 1 &&
    !missingQuestion;

  const chooseBlueprint = (value: string | null) => {
    const id = value ? Number(value) : null;
    const blueprint = blueprints.find((item) => item.id === id);
    setDraft((current) => ({ ...current, blueprintId: id, parameters: defaultsFor(blueprint) }));
  };

  const submit = async () => {
    try {
      const mission = await actions.createMission.mutateAsync(draft);
      notifications.show({
        color: mission.status === 'NEEDS_BINDING' ? 'yellow' : 'green',
        title: 'Mission planned',
        message:
          mission.status === 'NEEDS_BINDING'
            ? 'The coverage plan is ready; bind a delivery product before launch.'
            : `${mission.coverage.caseCount || mission.cases.length || 1} scenario cases are ready for review.`
      });
      onCreated(mission);
    } catch (error) {
      notifications.show({ color: 'red', title: 'Could not plan mission', message: errorMessage(error) });
    }
  };

  return (
    <Drawer
      opened={opened}
      onClose={onClose}
      position="right"
      size={560}
      title={null}
      className="scenario-mission-drawer"
    >
      <Stack gap="lg">
        <div className="scenario-workspace-heading">
          <span><IconSparkles size={19} /></span>
          <div>
            <Text fw={850} size="lg">New test data mission</Text>
            <Text size="sm" c="dimmed">
              Describe the state your test needs. ForgeTDM compiles coverage and uses an approved delivery product.
            </Text>
          </div>
        </div>

        <Select
          label="Scenario Blueprint"
          description="Reusable business outcome and coverage rules"
          searchable
          data={blueprints.map((item) => ({
            value: String(item.id),
            label: `${item.name} / ${item.domainName}`
          }))}
          value={draft.blueprintId ? String(draft.blueprintId) : null}
          onChange={chooseBlueprint}
          placeholder="Choose a published scenario"
        />
        {selected ? (
          <div className="scenario-selected-blueprint">
            <Text fw={750}>{selected.entityType}</Text>
            <Text size="sm" c="dimmed">{selected.description}</Text>
            <Text size="xs" c="blue">Version {selected.versionNo} / {selected.domainName}</Text>
          </div>
        ) : null}

        <TextInput
          label="Mission name"
          description="8-160 characters; this becomes the tester-visible run identity"
          value={draft.title}
          onChange={(event) => setDraft({ ...draft, title: event.currentTarget.value })}
          placeholder="Card decline boundary coverage"
          maxLength={160}
        />
        <Textarea
          label="What must your test prove?"
          description="State the behavior, precondition, and expected outcome. Do not paste production data."
          minRows={4}
          value={draft.intent}
          onChange={(event) => setDraft({ ...draft, intent: event.currentTarget.value })}
          placeholder="Validate that an active premium customer at the credit limit receives the expected decline response across card and ledger systems."
          maxLength={4000}
        />

        <SimpleGrid cols={{ base: 1, sm: 2 }}>
          <Select
            label="Target environment"
            data={['DEV', 'QA', 'SIT', 'UAT', 'PERF']}
            value={draft.targetEnvironment}
            onChange={(value) => setDraft({ ...draft, targetEnvironment: value || 'QA' })}
          />
          <Select
            label="Data strategy"
            description="AUTO lets the Blueprint choose safely"
            data={[
              { value: 'AUTO', label: 'Auto-select best strategy' },
              { value: 'SUBSET', label: 'Masked subset' },
              { value: 'SYNTHETIC', label: 'Synthetic data' },
              { value: 'HYBRID', label: 'Hybrid subset + synthetic' },
              { value: 'CLONE', label: 'Virtual clone' },
              { value: 'SNAPSHOT', label: 'Point-in-time snapshot' }
            ]}
            value={draft.sourceStrategy}
            onChange={(value) => setDraft({ ...draft, sourceStrategy: value || 'AUTO' })}
          />
        </SimpleGrid>
        <NumberInput
          label="Requested entities / rows"
          min={1}
          max={100_000_000}
          thousandSeparator=","
          value={draft.requestedCount}
          onChange={(value) =>
            setDraft({ ...draft, requestedCount: typeof value === 'number' ? value : 1 })
          }
        />

        {(selected?.questionnaire || []).length ? (
          <>
            <Divider label="Scenario choices" labelPosition="left" />
            <SimpleGrid cols={{ base: 1, sm: 2 }}>
              {selected?.questionnaire.map((field) => (
                <QuestionField
                  key={field.key}
                  field={field}
                  value={draft.parameters[field.key]}
                  onChange={(value) =>
                    setDraft({ ...draft, parameters: { ...draft.parameters, [field.key]: value } })
                  }
                />
              ))}
            </SimpleGrid>
          </>
        ) : null}

        <div className="scenario-reservation">
          <Switch
            label="Reserve this test data for my team"
            description="Prevents another mission from claiming the same prepared dataset."
            checked={draft.reservationRequested}
            onChange={(event) =>
              setDraft({ ...draft, reservationRequested: event.currentTarget.checked })
            }
          />
          <NumberInput
            label="Hours"
            min={1}
            max={720}
            disabled={!draft.reservationRequested}
            value={draft.reservationHours}
            onChange={(value) =>
              setDraft({ ...draft, reservationHours: typeof value === 'number' ? value : 24 })
            }
            w={120}
          />
        </div>

        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>Cancel</Button>
          <Button
            rightSection={<IconArrowRight size={16} />}
            loading={actions.createMission.isPending}
            disabled={!valid}
            onClick={submit}
          >
            Compile mission
          </Button>
        </Group>
      </Stack>
    </Drawer>
  );
}

function QuestionField({
  field,
  value,
  onChange
}: {
  field: QuestionnaireField;
  value: unknown;
  onChange: (value: unknown) => void;
}) {
  const options = (field.options || []).map((item) => ({ value: textValue(item), label: textValue(item) }));
  if (options.length) {
    return (
      <Select
        label={field.label || field.key}
        required={field.required}
        data={options}
        value={textValue(value) || null}
        onChange={onChange}
      />
    );
  }
  return (
    <TextInput
      label={field.label || field.key}
      required={field.required}
      value={textValue(value)}
      onChange={(event) => onChange(event.currentTarget.value)}
    />
  );
}

function defaultsFor(blueprint?: Blueprint): JsonMap {
  const values: JsonMap = {};
  for (const field of blueprint?.questionnaire || []) {
    const first = field.defaultValue ?? field.options?.[0] ?? '';
    values[field.key] = first;
  }
  return values;
}
