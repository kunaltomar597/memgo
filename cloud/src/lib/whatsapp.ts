import { required } from "./env";

/**
 * WhatsApp Cloud API client (TASK-062). Sends the approved member notice template by name with
 * the body variables shop_name, first_name and date and the "👍" quick-reply button.
 * Template names come from WHATSAPP_TEMPLATE_MEMBER_NOTICE_EN and _HI.
 */
export type NoticeLanguage = "en" | "hi";

export const THUMBS_UP = "👍";

export interface NoticeParams {
  to: string; // E.164, e.g. +919876543210
  language: NoticeLanguage;
  shopName: string;
  firstName: string;
  date: string;
}

export interface WhatsAppConfig {
  phoneNumberId?: string;
  accessToken?: string;
  templateEn?: string;
  templateHi?: string;
  graphVersion: string;
}

export class WhatsAppSendError extends Error {
  constructor(
    readonly httpStatus: number,
    readonly code: string,
    readonly retryable: boolean,
  ) {
    super(`whatsapp send failed: ${httpStatus} ${code}`);
    this.name = "WhatsAppSendError";
  }
}

export interface WhatsAppClient {
  templateName(language: NoticeLanguage): string;
  sendNotice(p: NoticeParams): Promise<{ wamid: string }>;
}

export function buildNoticeBody(templateName: string, p: NoticeParams) {
  return {
    messaging_product: "whatsapp",
    recipient_type: "individual",
    to: p.to.replace(/^\+/, ""),
    type: "template",
    template: {
      name: templateName,
      language: { code: p.language },
      components: [
        {
          type: "body",
          parameters: [
            { type: "text", parameter_name: "shop_name", text: p.shopName },
            { type: "text", parameter_name: "first_name", text: p.firstName },
            { type: "text", parameter_name: "date", text: p.date },
          ],
        },
        {
          type: "button",
          sub_type: "quick_reply",
          index: "0",
          parameters: [{ type: "payload", payload: THUMBS_UP }],
        },
      ],
    },
  };
}

export function createWhatsAppClient(cfg: WhatsAppConfig, fetchImpl: typeof fetch = fetch): WhatsAppClient {
  const templateName = (language: NoticeLanguage) =>
    language === "hi"
      ? required(cfg.templateHi, "WHATSAPP_TEMPLATE_MEMBER_NOTICE_HI")
      : required(cfg.templateEn, "WHATSAPP_TEMPLATE_MEMBER_NOTICE_EN");
  return {
    templateName,
    async sendNotice(p) {
      const phoneNumberId = required(cfg.phoneNumberId, "WHATSAPP_PHONE_NUMBER_ID");
      const token = required(cfg.accessToken, "WHATSAPP_ACCESS_TOKEN");
      const body = buildNoticeBody(templateName(p.language), p);
      let res: Response;
      try {
        res = await fetchImpl(`https://graph.facebook.com/${cfg.graphVersion}/${phoneNumberId}/messages`, {
          method: "POST",
          headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
          body: JSON.stringify(body),
        });
      } catch {
        throw new WhatsAppSendError(0, "network", true);
      }
      const data = (await res.json().catch(() => ({}))) as {
        messages?: { id?: string }[];
        error?: { code?: number };
      };
      if (!res.ok) {
        const code = String(data.error?.code ?? res.status);
        throw new WhatsAppSendError(res.status, code, res.status >= 500 || res.status === 429);
      }
      const wamid = data.messages?.[0]?.id;
      if (!wamid) throw new WhatsAppSendError(res.status, "no_wamid", true);
      return { wamid };
    },
  };
}
