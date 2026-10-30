<script setup>
import { onMounted, onBeforeUnmount, ref } from 'vue'

const props = defineProps({ id: String, label: String, catalog: String, modelValue: [String, Number] })
const emit = defineEmits(['update:modelValue', 'selected'])
const items = ref([])
const selected = ref(null)
const page = ref(0)
const hasNext = ref(false)
const loading = ref(false)
const error = ref('')
let controller

async function load(targetPage = 0) {
  controller?.abort()
  const request = new AbortController()
  controller = request
  loading.value = true
  error.value = ''
  try {
    const params = new URLSearchParams({ page: targetPage, size: 20 })
    const response = await fetch(`/api/reference-data/${props.catalog}?${params}`, { signal: request.signal })
    const body = await response.json()
    if (!response.ok) throw new Error(body.message || `Could not load ${props.label.toLowerCase()}.`)
    items.value = body.items
    page.value = body.page
    hasNext.value = body.hasNext
    if (!selected.value && items.value.length) select(items.value[0].id)
  } catch (failure) {
    if (failure.name !== 'AbortError') error.value = failure.message
  } finally {
    if (controller === request) loading.value = false
  }
}

function select(id) {
  selected.value = items.value.find(item => String(item.id) === String(id)) ?? selected.value
  emit('update:modelValue', selected.value?.id ?? '')
  emit('selected', selected.value)
}

onMounted(() => load())
onBeforeUnmount(() => controller?.abort())
</script>

<template>
  <div class="catalog">
    <label :for="id">{{ label }}</label>
    <select :id="id" :value="modelValue" required :disabled="loading" @change="select($event.target.value)">
      <option value="" disabled>Select an option</option>
      <option v-if="selected && !items.some(item => item.id === selected.id)" :value="selected.id">{{ selected.label || selected.name }}</option>
      <option v-for="item in items" :key="item.id" :value="item.id">{{ item.label || item.name }}</option>
    </select>
    <div class="catalog-pages">
      <button type="button" :disabled="loading || page === 0" @click="load(page - 1)">Previous</button>
      <span role="status">{{ loading ? 'Loading…' : items.length ? `Page ${page + 1}` : 'No options' }}</span>
      <button type="button" :disabled="loading || !hasNext" @click="load(page + 1)">Next</button>
    </div>
    <p v-if="error" role="alert">{{ error }} <button type="button" @click="load(page)">Retry</button></p>
  </div>
</template>

<style scoped>
.catalog { display: flex; flex-direction: column; margin-bottom: 19px; }
.catalog select { margin-bottom: 8px; }
.catalog-pages { display: flex; align-items: center; justify-content: space-between; gap: 12px; font-size: 12px; }
.catalog button { border: 1px solid #dfe5e0; background: white; border-radius: 5px; padding: 5px 9px; cursor: pointer; }
.catalog button:disabled { opacity: .5; cursor: default; }
.catalog p { color: #76541d; font-size: 12px; }
</style>
