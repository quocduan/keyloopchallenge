<script setup>
import { computed, reactive, ref } from 'vue'
import CatalogSelect from './components/CatalogSelect.vue'

const selectedService = ref(null)
const form = reactive({ dealershipId: '', vehicleId: '', serviceTypeId: '', desiredStart: '' })
const status = ref('ready')
const message = ref('')
const appointment = ref(null)
const availability = ref(null)
const busy = ref(false)
const simulateDuplicate = ref(false)
let idempotencyAttempt = null

const durationLabel = computed(() => {
  const minutes = selectedService.value?.durationMinutes ?? 0
  return minutes >= 60 ? `${Math.floor(minutes / 60)}${minutes % 60 ? `h ${minutes % 60}m` : 'h'}` : `${minutes} minutes`
})

function buildQuery() {
  const query = new URLSearchParams({
    dealershipId: form.dealershipId,
    vehicleId: form.vehicleId,
    serviceTypeId: form.serviceTypeId,
    desiredStart: form.desiredStart,
  })
  return query.toString()
}

async function checkAvailability() {
  appointment.value = null
  availability.value = null
  message.value = ''
  if (!form.desiredStart) {
    message.value = 'Choose a date and time first.'
    status.value = 'error'
    return
  }
  busy.value = true
  try {
    const response = await fetch(`/api/availability?${buildQuery()}`)
    const body = await response.json()
    if (!response.ok) throw new Error(body.message || 'Availability check failed.')
    availability.value = body
    status.value = body.available ? 'available' : 'unavailable'
    message.value = body.message
  } catch (error) {
    status.value = 'error'
    message.value = error.message
  } finally {
    busy.value = false
  }
}

async function bookAppointment() {
  if (!form.desiredStart) return
  busy.value = true
  message.value = ''
  status.value = 'processing'
  const payload = {
    dealershipId: Number(form.dealershipId),
    vehicleId: Number(form.vehicleId),
    serviceTypeId: Number(form.serviceTypeId),
    desiredStart: form.desiredStart,
  }
  const payloadSignature = JSON.stringify(payload)
  if (!idempotencyAttempt || idempotencyAttempt.payloadSignature !== payloadSignature) {
    idempotencyAttempt = { payloadSignature, key: newIdempotencyKey() }
  }
  const key = idempotencyAttempt.key
  try {
    message.value = simulateDuplicate.value
      ? 'Sending two requests with the same idempotency key…'
      : 'Submitting your booking…'
    let result
    if (simulateDuplicate.value) {
      const first = submitBooking(payload, key)
      await delay(25)
      const second = submitBooking(payload, key)
      ;[result] = await Promise.all([first, second])
    } else {
      result = await submitBooking(payload, key)
    }
    appointment.value = result
    availability.value = null
    status.value = 'confirmed'
    message.value = ''
    idempotencyAttempt = null
  } catch (error) {
    status.value = 'error'
    message.value = error.message
  } finally {
    busy.value = false
  }
}

async function submitBooking(payload, key) {
  const headers = { 'Content-Type': 'application/json', 'Idempotency-Key': key }
  if (simulateDuplicate.value) headers['X-Manual-Booking-Delay'] = 'true'
  const response = await fetch('/api/appointments', {
      method: 'POST',
      headers,
      body: JSON.stringify(payload),
  })
  const body = await response.json()
  if (response.status === 202) {
    status.value = 'processing'
    message.value = 'The request is being processed. Checking for the confirmed appointment…'
    return pollIdempotentResult(key)
  }
  if (!response.ok) throw new Error(body.message || 'Booking failed.')
  return body
}

async function pollIdempotentResult(key) {
  while (true) {
    await delay(1000)
    const response = await fetch('/api/appointments/idempotency-result', {
      headers: { 'Idempotency-Key': key },
    })
    const body = await response.json()
    if (response.status === 202) {
      message.value = 'Your booking is still processing. Checking again shortly…'
      continue
    }
    if (!response.ok) throw new Error(body.message || 'Could not retrieve the booking result.')
    return body
  }
}

function newIdempotencyKey() {
  return globalThis.crypto?.randomUUID?.() ?? `booking-${Date.now()}-${Math.random().toString(36).slice(2)}`
}

function delay(milliseconds) {
  return new Promise(resolve => setTimeout(resolve, milliseconds))
}

function formatDate(value) {
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}

</script>

<template>
  <main class="shell">
    <header class="topbar">
      <a class="brand" href="#" aria-label="Keyloop home"><span class="brand-mark">K</span><span>keyloop</span></a>
      <div class="topbar-right"><span class="secure-dot"></span> Service booking <span class="avatar">JD</span></div>
    </header>

    <section class="page-heading">
      <div class="eyebrow">KEYLOOP SERVICE · ONLINE BOOKING</div>
      <h1>Book a service</h1>
      <p>Choose your vehicle and service, then find a time that works for you.</p>
    </section>

    <div class="layout">
      <section class="booking-card">
        <div class="card-heading"><div><span class="step-label">YOUR APPOINTMENT</span><h2>Service details</h2></div><span class="step-count">01 <i>/ 01</i></span></div>
        <form @submit.prevent="checkAvailability">
          <CatalogSelect id="dealer" v-model="form.dealershipId" label="Dealership" catalog="dealerships" />
          <CatalogSelect id="vehicle" v-model="form.vehicleId" label="Your vehicle" catalog="vehicles" />
          <CatalogSelect id="service" v-model="form.serviceTypeId" label="Service required" catalog="service-types" @selected="selectedService = $event" />
          <div v-if="selectedService" class="field-hint">Estimated time: {{ durationLabel }}</div>

          <label for="date">Preferred date and time</label>
          <input id="date" v-model="form.desiredStart" type="datetime-local" required />

          <button class="primary-button" type="submit" :disabled="busy || !form.dealershipId || !form.vehicleId || !form.serviceTypeId">
            <span v-if="busy" class="spinner"></span>{{ busy ? (status === 'processing' ? 'Booking…' : 'Checking…') : 'Check availability' }} <span aria-hidden="true">→</span>
          </button>
          <label class="test-mode"><input v-model="simulateDuplicate" type="checkbox" /> <span>Simulate duplicate request (15-second manual test)</span></label>
        </form>

        <div v-if="status === 'available' && availability" class="notice success">
          <span class="notice-icon">✓</span><div><strong>Time available</strong><p>{{ formatDate(availability.startsAt) }} to {{ formatDate(availability.endsAt) }}</p><button class="confirm-button" @click="bookAppointment" :disabled="busy">Confirm appointment <span>→</span></button></div>
        </div>
        <div v-else-if="status === 'unavailable'" class="notice warning"><span class="notice-icon">!</span><div><strong>No matching resources available</strong><p>{{ message }} Try another time.</p></div></div>
        <div v-else-if="status === 'error'" class="notice warning"><span class="notice-icon">!</span><div><strong>We couldn’t complete that request</strong><p>{{ message }}</p></div></div>
        <div v-else-if="status === 'processing'" class="notice success" role="status" aria-live="polite">
          <span class="notice-icon"><span class="spinner spinner-dark"></span></span><div><strong>Booking in progress</strong><p>{{ message }}</p></div>
        </div>
        <div v-else-if="status === 'confirmed' && appointment" class="notice success confirmation">
          <span class="notice-icon">✓</span><div><strong>Appointment confirmed</strong><p>Reference #{{ appointment.id }}</p><p>{{ appointment.serviceName }} · {{ appointment.vehicleLabel }}</p><p>{{ formatDate(appointment.startsAt) }}</p><p>{{ appointment.technicianName }} · {{ appointment.serviceBayName }}</p></div>
        </div>
      </section>

      <aside class="side-column">
        <section class="help-card"><div class="help-icon">✳</div><div class="eyebrow">HERE TO HELP</div><h3>Need a hand?</h3><p>Our service team can help you find the right appointment for your vehicle.</p><a href="tel:+441234567890">Call us <span>↗</span></a></section>
        <section class="trust-card"><div class="trust-title"><span>✓</span> Your booking, taken care of</div><div class="trust-item"><span class="trust-icon">◷</span><span>We’ll check both technician and bay availability.</span></div><div class="trust-item"><span class="trust-icon">▣</span><span>Your appointment details are saved securely.</span></div></section>
      </aside>
    </div>
    <footer><span>© Keyloop</span><span>Need assistance? <a href="tel:+441234567890">Contact the dealership</a></span><span>Privacy&nbsp;&nbsp;&nbsp; Terms</span></footer>
  </main>
</template>

<style scoped>
.booking-card form .test-mode {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 12px 0 0;
  color: #64736b;
  font-size: 11px;
  font-weight: 500;
  cursor: pointer;
}

.booking-card form .test-mode input {
  width: 15px;
  height: 15px;
  margin: 0;
  accent-color: #13885f;
}

.spinner-dark {
  border-color: #15885f40;
  border-top-color: #15885f;
}
</style>
