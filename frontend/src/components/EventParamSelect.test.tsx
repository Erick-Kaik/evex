import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { EventParamSelect } from './EventParamSelect'
import type { EventParamKind } from './contractCatalog'
import type { ContractEventInput } from '../api/api'

const inputs: ContractEventInput[] = [
  { name: 'from', type: 'address', indexed: true },
  { name: 'to', type: 'address', indexed: true },
  { name: 'value', type: 'uint256', indexed: false },
  { name: 'count', type: 'int128', indexed: false },
  { name: 'flags', type: 'uint8[]', indexed: false },
  { name: 'data', type: 'bytes', indexed: false },
  { name: 'ok', type: 'bool', indexed: false },
]

function Harness({ kind }: { kind: EventParamKind }) {
  const [value, setValue] = useState('')
  return (
    <>
      <span data-testid="value">{value}</span>
      <EventParamSelect kind={kind} inputs={inputs} value={value} onChange={setValue} label="Parâmetro" />
    </>
  )
}

async function openOptions(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('combobox', { name: 'Parâmetro' }))
  const listbox = await screen.findByRole('listbox')
  return within(listbox)
    .getAllByRole('option')
    .map((option) => option.textContent)
}

describe('EventParamSelect', () => {
  it('kind="address" só lista inputs do tipo address', async () => {
    const user = userEvent.setup()
    render(<Harness kind="address" />)
    const options = await openOptions(user)
    expect(options).toEqual(['from (address)', 'to (address)'])
  })

  it('kind="numeric" lista uint*/int* (inclusive arrays), nada mais', async () => {
    const user = userEvent.setup()
    render(<Harness kind="numeric" />)
    const options = await openOptions(user)
    expect(options).toEqual(['value (uint256)', 'count (int128)', 'flags (uint8[])'])
  })

  it('kind="numericScalar" só escalares e kind="numericArray" só arrays numéricos', async () => {
    const user = userEvent.setup()
    const { unmount } = render(<Harness kind="numericScalar" />)
    expect(await openOptions(user)).toEqual(['value (uint256)', 'count (int128)'])
    unmount()
    render(<Harness kind="numericArray" />)
    expect(await openOptions(user)).toEqual(['flags (uint8[])'])
  })

  it('kind="any" lista todos os inputs', async () => {
    const user = userEvent.setup()
    render(<Harness kind="any" />)
    const options = await openOptions(user)
    expect(options).toHaveLength(inputs.length)
  })

  it('emite o nome do parâmetro escolhido', async () => {
    const user = userEvent.setup()
    render(<Harness kind="numeric" />)
    await user.click(screen.getByRole('combobox', { name: 'Parâmetro' }))
    await user.click(await screen.findByRole('option', { name: 'value (uint256)' }))
    expect(screen.getByTestId('value')).toHaveTextContent('value')
  })

  it('fica desabilitado sem inputs', () => {
    render(<EventParamSelect kind="any" inputs={[]} value="" onChange={() => {}} label="Parâmetro" />)
    expect(screen.getByRole('combobox', { name: 'Parâmetro' })).toHaveAttribute('aria-disabled', 'true')
  })
})
