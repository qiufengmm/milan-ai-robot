const MODEL_CHOICES = [
  { id: 1, name: 'qwen3.8-flash', icon: 'qwen-logo', description: '极速响应' },
  { id: 2, name: 'qwen3.8-27b', icon: 'qwen-logo', description: '均衡全能' },
  { id: 3, name: 'kimi-k3', icon: 'kimi-logo', description: '超长上下文' },
]

// Persisted model objects are untrusted snapshots of an older menu. Rebuild the
// menu on hydration, retaining only a choice that is still available today.
export function restoreChatSelection(persisted) {
  const selectedName = persisted?.selectedModel?.name
  const name = MODEL_CHOICES.some(model => model.name === selectedName) ? selectedName : MODEL_CHOICES[0].name
  const models = MODEL_CHOICES.map(model => ({ ...model, selected: model.name === name }))
  return { models, selectedModel: models.find(model => model.selected) }
}
