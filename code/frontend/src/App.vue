<script setup>
import { computed, onMounted, reactive, ref } from 'vue'

const reference = ref({ dealerships: [], vehicles: [], serviceTypes: [] })
const form = reactive({ dealershipId: '', vehicleId: '', serviceTypeId: '', desiredStart: '' })
const status = ref('loading')
const message = ref('Loading booking options…')
const appointment = ref(null)
const availability = ref(null)
const busy = ref(false)

const selectedService = computed(() => reference.value.serviceTypes.find(x => x.id === Number(form.serviceTypeId)))
const durationLabel = computed(() => {
  const minutes = selectedService.value?.durationMinutes ?? 0
  return minutes >= 60 ? `${Math.floor(minutes / 60)}${minutes % 60 ? `h ${minutes % 60}m` : 'h'}` : `${minutes} minutes`
})

async function loadReferenceData() {
  try {
    const response = await fetch('/api/reference-data')
    if (!response.ok) throw new Error('Could not load booking options.')
    reference.value = await response.json()
    form.dealershipId = reference.value.dealerships[0]?.id ?? ''
    form.vehicleId = reference.value.vehicles[0]?.id ?? ''
    form.serviceTypeId = reference.value.serviceTypes[0]?.id ?? ''
    status.value = 'ready'
    message.value = ''
  } catch (error) {
    status.value = 'error'
    message.value = `${error.message} Make sure the API and MySQL are running.`
  }
}

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
  try {
    const response = await fetch('/api/appointments', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        dealershipId: Number(form.dealershipId),
        vehicleId: Number(form.vehicleId),
        serviceTypeId: Number(form.serviceTypeId),
        desiredStart: form.desiredStart,
      }),
    })
    const body = await response.json()
    if (!response.ok) throw new Error(body.message || 'Booking failed.')
    appointment.value = body
    availability.value = null
    status.value = 'confirmed'
  } catch (error) {
    status.value = 'error'
    message.value = error.message
  } finally {
    busy.value = false
  }
}

function formatDate(value) {
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}

onMounted(loadReferenceData)
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
          <label for="dealer">Dealership</label>
          <select id="dealer" v-model="form.dealershipId" required :disabled="status === 'loading'">
            <option value="" disabled>Select a dealership</option>
            <option v-for="dealer in reference.dealerships" :key="dealer.id" :value="dealer.id">{{ dealer.name }}</option>
          </select>

          <label for="vehicle">Your vehicle</label>
          <select id="vehicle" v-model="form.vehicleId" required>
            <option value="" disabled>Select a vehicle</option>
            <option v-for="vehicle in reference.vehicles" :key="vehicle.id" :value="vehicle.id">{{ vehicle.label }}</option>
          </select>

          <label for="service">Service required</label>
          <select id="service" v-model="form.serviceTypeId" required>
            <option value="" disabled>Select a service</option>
            <option v-for="service in reference.serviceTypes" :key="service.id" :value="service.id">{{ service.name }}</option>
          </select>
          <div v-if="selectedService" class="field-hint">Estimated time: {{ durationLabel }}</div>

          <label for="date">Preferred date and time</label>
          <input id="date" v-model="form.desiredStart" type="datetime-local" required />

          <button class="primary-button" type="submit" :disabled="busy || status === 'loading'">
            <span v-if="busy" class="spinner"></span>{{ busy ? 'Checking…' : 'Check availability' }} <span aria-hidden="true">→</span>
          </button>
        </form>

        <div v-if="status === 'available' && availability" class="notice success">
          <span class="notice-icon">✓</span><div><strong>Time available</strong><p>{{ formatDate(availability.startsAt) }} to {{ formatDate(availability.endsAt) }}</p><button class="confirm-button" @click="bookAppointment" :disabled="busy">Confirm appointment <span>→</span></button></div>
        </div>
        <div v-else-if="status === 'unavailable'" class="notice warning"><span class="notice-icon">!</span><div><strong>No matching resources available</strong><p>{{ message }} Try another time.</p></div></div>
        <div v-else-if="status === 'error'" class="notice warning"><span class="notice-icon">!</span><div><strong>We couldn’t complete that request</strong><p>{{ message }}</p></div></div>
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
