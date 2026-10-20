import express from 'express';

const app = express();
app.use(express.json({ limit: '8mb' }));
const PORT = Number(process.env.PORT || 3000);
const API_KEY = process.env.OPENAI_API_KEY;
const MODEL = process.env.OPENAI_MODEL || 'gpt-4.1-mini';

app.get('/health', (_req, res) => res.json({ ok: true, service: 'NutriPlan Food AI' }));
app.post('/analyze-food', async (req, res) => {
  try {
    if (!API_KEY) return res.status(503).json({ error: 'Falta configurar OPENAI_API_KEY en el servidor.' });
    const { imageBase64, mimeType = 'image/jpeg' } = req.body || {};
    if (typeof imageBase64 !== 'string' || imageBase64.length < 100) return res.status(400).json({ error: 'Imagen no válida.' });
    if (!['image/jpeg', 'image/png', 'image/webp'].includes(mimeType)) return res.status(400).json({ error: 'Formato de imagen no permitido.' });
    if (imageBase64.length > 7_000_000) return res.status(413).json({ error: 'Imagen demasiado grande.' });

    const prompt = `Analiza la fotografía de comida para una app de nutrición. Devuelve SOLO un objeto JSON válido, sin markdown, con esta estructura exacta: {"name":"descripción breve en español","grams":250,"kcal":450,"protein":25,"carbs":40,"fat":15,"items":[{"name":"alimento","grams":150,"kcal":250}]}. Estima el peso total comestible y las calorías/macronutrientes de toda la porción visible. Los gramos y macros deben ser números, kcal entero. No afirmes precisión que no puedes obtener de una imagen. Si no puedes reconocer comida, devuelve name="No se reconoce el alimento con seguridad", grams=0, kcal=0, protein=0, carbs=0, fat=0, items=[]. Ten en cuenta que aceite, salsas y el volumen real son difíciles de estimar; usa una estimación razonable y conservadora.`;
    const apiResponse = await fetch('https://api.openai.com/v1/responses', {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${API_KEY}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        model: MODEL,
        store: false,
        input: [{ role: 'user', content: [
          { type: 'input_text', text: prompt },
          { type: 'input_image', image_url: `data:${mimeType};base64,${imageBase64}`, detail: 'high' }
        ] }],
        max_output_tokens: 500
      })
    });
    const data = await apiResponse.json();
    if (!apiResponse.ok) {
      console.error('OpenAI API error:', apiResponse.status, JSON.stringify(data).slice(0, 1000));
      return res.status(502).json({ error: 'El servicio de análisis de IA ha devuelto un error. Revisa la clave, el saldo y el modelo configurado.' });
    }
    const text = (data.output || []).flatMap(item => item.content || []).filter(c => c.type === 'output_text').map(c => c.text).join('\n').trim();
    if (!text) return res.status(502).json({ error: 'La IA no ha devuelto un resultado legible.' });
    const cleaned = text.replace(/^```(?:json)?\s*/i, '').replace(/\s*```$/, '').trim();
    const food = JSON.parse(cleaned);
    const numberFields = ['grams', 'kcal', 'protein', 'carbs', 'fat'];
    for (const key of numberFields) if (!Number.isFinite(Number(food[key])) || Number(food[key]) < 0) food[key] = 0;
    food.name = String(food.name || 'Comida identificada').slice(0, 180);
    return res.json({ food });
  } catch (err) {
    console.error('Analysis error:', err);
    return res.status(500).json({ error: 'No se ha podido analizar la imagen.' });
  }
});

app.listen(PORT, '0.0.0.0', () => console.log(`NutriPlan Food AI escuchando en puerto ${PORT}`));
