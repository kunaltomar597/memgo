// Checks that the newest visit of MEMBER_ID has greeted=true on the hub (TASK-084).
const res = http.get(`${HUB_URL}/visits?member_id=${MEMBER_ID}`, { headers: { Authorization: `Bearer ${HUB_TOKEN}` } })
if (res.status !== 200) throw new Error(`hub answered ${res.status}`)
const visits = json(res.body)
if (visits.length === 0) throw new Error('no visits')
if (visits[0].greeted !== true) throw new Error('newest visit is not greeted')
