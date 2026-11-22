/**
 * Messages the bridge itself shows on the phone, in the phone's language. The English text is
 * the key; {0}, {1} are replaced with the arguments. The language is whatever the phone that
 * talked to the bridge last asked for (the app sends it on every request).
 */

export type Language = "en" | "he";

let current: Language = "en";

export function setLanguage(value: string | null | undefined): void {
  if (!value) return;
  current = /^(he|iw)/i.test(value) ? "he" : "en";
}

const HEBREW: Record<string, string> = {
  "Message is empty": "ההודעה ריקה",
  "Antigravity CLI (agy) was not found on the PC. Install Antigravity and run agy once.":
    "ה-CLI של Antigravity (agy) לא נמצא במחשב. התקן את Antigravity והרץ agy פעם אחת.",
  "Waiting ({0}). It will be sent when the agent finishes.": "ממתין ({0}). ההודעה תישלח כשהסוכן יסיים.",
  "Stopping": "עוצר",
  "Stopping (removed {0} waiting messages)": "עוצר (הוסרו {0} הודעות ממתינות)",
  "Nothing is running": "שום דבר לא רץ",
  "New conversation": "שיחה חדשה",
  "Now working in {0}": "עובד עכשיו בפרויקט {0}",
  "Could not start the agent: {0}": "לא ניתן להפעיל את הסוכן: {0}",
  "Stopped": "נעצר",
  "Turn ended: {0}": "התור הסתיים: {0}",
  "The message failed: {0}": "ההודעה נכשלה: {0}",
  "{0}. Run agy once on the PC to sign in.": "{0}. הרץ agy פעם אחת במחשב כדי להתחבר לחשבון.",
  "The agent stopped unexpectedly ({0})": "הסוכן נעצר באופן לא צפוי ({0})",
  "Plan": "תוכנית",
  "Approval needed: {0}": "נדרש אישור: {0}",
  "Nothing is waiting for approval": "אין שום דבר שממתין לאישור",
  "Approved: {0}": "אושר: {0}",
  "Rejected: {0}": "נדחה: {0}",
  "Access denied: that path is outside the project": "הגישה נדחתה: הנתיב מחוץ לפרויקט",
  "Folder not found": "התיקייה לא נמצאה",
  "File not found": "הקובץ לא נמצא",
  "That conversation was not found in Antigravity": "השיחה לא נמצאה ב-Antigravity",
  "Following the IDE conversation: {0}": "עוקב אחרי שיחת ה-IDE: {0}",
  "Antigravity IDE is not running on the PC": "Antigravity IDE לא פתוח במחשב",
  "Antigravity did not create the conversation": "Antigravity לא יצר את השיחה",
  "No IDE conversation is open": "אין שיחת IDE פתוחה",
  "Lost contact with the IDE: {0}": "הקשר עם ה-IDE אבד: {0}",
  "Antigravity IDE was not found on this PC": "Antigravity IDE לא נמצא במחשב",
  "Not a folder: {0}": "זו לא תיקייה: {0}",
  "Project: {0}": "פרויקט: {0}",
  "Opened {0} in Antigravity IDE": "{0} נפתח ב-Antigravity IDE",
  "Back to the agent on the PC": "חזרה לסוכן שבמחשב",
  "Command is empty": "הפקודה ריקה",
  "\"{0}\" is still running. Stop it first, or type a reply to it.": "\"{0}\" עדיין רץ. עצור אותו קודם, או הקלד לו תשובה.",
  "No command is running": "שום פקודה לא רצה",
  "Stopped: {0}": "נעצר: {0}",
  "cd: no such folder: {0}": "cd: אין תיקייה כזו: {0}",
};

export function t(english: string, ...args: unknown[]): string {
  const template = current === "he" ? (HEBREW[english] ?? english) : english;
  return template.replace(/\{(\d+)\}/g, (_, index: string) => String(args[Number(index)] ?? ""));
}
