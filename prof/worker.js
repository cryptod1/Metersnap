const ALLOWED_ORIGINS = new Set([
  "https://situsnap.app",
  "https://www.situsnap.app",
  "https://cryptod1.github.io"
]);

function cors(origin) {
  const allow = ALLOWED_ORIGINS.has(origin) ? origin : "https://situsnap.app";
  return {
    "Access-Control-Allow-Origin": allow,
    "Vary": "Origin",
    "Access-Control-Allow-Methods": "POST,OPTIONS,GET",
    "Access-Control-Allow-Headers": "Content-Type",
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store"
  };
}

const SYSTEM = `You are SituSnap Prof, a cautious UK electricity/gas meter image analyst used for a controlled training evaluation.
Analyse only what is visible in the supplied image. Never use an expected serial number and never invent unreadable digits.
Identify manufacturer/model from the complete visual evidence: labels, casing, controls/keypad, display layout and printed text.
Read the meter serial only when sufficiently legible. Preserve leading zeroes in readings.
For electricity, distinguish import from export and keep Rate 1/Rate 2 separate. A+ normally denotes active import and A- active export, but rely on the visible meter label/register configuration rather than assumption.
A dark/blank display is NOT a zero reading. Set blank_display true only if the display is visibly blank/dark; do not claim the meter is faulty merely from one image. how_to_read should explain the appropriate model-specific wake/read procedure when the model is confidently identified, otherwise ask for a closer label view.
For visible condition observations, do not accuse a person of tampering and do not declare an installation safe. Only flag concrete visible anomalies. "Nothing visible" is not a safety certificate.
Return JSON only.`;

const SCHEMA = `{
 "model": string,
 "manufacturer": string,
 "fuel": "electricity"|"gas"|"unknown",
 "serial": string,
 "serial_confidence": number,
 "reading_mode": "single"|"dual"|"importexport"|"blank",
 "registers": [{"label":string,"reading":string,"digits_required":number|null,"confidence":number}],
 "blank_display": boolean,
 "display_observation": string,
 "how_to_read": string,
 "visible_concern":{"detected":boolean,"observation":string},
 "bypass_tamper_concern":{"detected":boolean,"observation":string},
 "confidence": number,
 "needs_better_image": boolean,
 "better_image_instruction": string
}`;

function extractText(data) {
  if (typeof data.output_text === "string" && data.output_text) return data.output_text;
  for (const item of data.output || []) {
    for (const c of item.content || []) if (c.type === "output_text" && c.text) return c.text;
  }
  return "";
}
function jsonFromText(t) {
  t=t.trim().replace(/^```json\s*/i,"").replace(/```$/,"").trim();
  return JSON.parse(t);
}

export default {
  async fetch(request, env) {
    const origin=request.headers.get("Origin")||"";
    if(request.method==="OPTIONS") return new Response(null,{status:204,headers:cors(origin)});
    if(request.method==="GET") return new Response(JSON.stringify({ok:true,service:"SituSnap Prof Vision",configured:!!env.OPENAI_API_KEY}),{headers:cors(origin)});
    if(request.method!=="POST") return new Response(JSON.stringify({error:"Method not allowed"}),{status:405,headers:cors(origin)});
    if(!ALLOWED_ORIGINS.has(origin)) return new Response(JSON.stringify({error:"Origin not allowed"}),{status:403,headers:cors(origin)});
    if(!env.OPENAI_API_KEY) return new Response(JSON.stringify({error:"Prof vision service is not configured"}),{status:503,headers:cors(origin)});
    try{
      const body=await request.json();
      if(typeof body.image!=="string" || !body.image.startsWith("data:image/")) throw new Error("Missing meter image");
      if(body.image.length>8_000_000) throw new Error("Image too large");
      const payload={
        model:"gpt-5.6-luna",
        input:[{role:"user",content:[
          {type:"input_text",text:SYSTEM+"\nReturn exactly this JSON shape:\n"+SCHEMA+"\nAnalyse this meter image now."},
          {type:"input_image",image_url:body.image,detail:"high"}
        ]}],
        max_output_tokens:1800
      };
      const r=await fetch("https://api.openai.com/v1/responses",{method:"POST",headers:{
        "Authorization":`Bearer ${env.OPENAI_API_KEY}`,
        "Content-Type":"application/json"
      },body:JSON.stringify(payload)});
      const data=await r.json();
      if(!r.ok) throw new Error(data?.error?.message||`Vision API ${r.status}`);
      const result=jsonFromText(extractText(data));
      result.service_version="prof-vision-0.4";
      return new Response(JSON.stringify(result),{headers:cors(origin)});
    }catch(e){
      return new Response(JSON.stringify({error:e.message||"Prof analysis failed"}),{status:500,headers:cors(origin)});
    }
  }
};