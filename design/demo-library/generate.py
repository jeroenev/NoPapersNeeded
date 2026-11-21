#!/usr/bin/env python3
"""Generate the fictional PDFs of the demo library used for app screenshots.

Every person, company, address, bank account, tax number and amount in these
documents is invented; nothing here refers to anyone or anything real. Keep it
that way when adding documents: no real names, real company names, real
addresses, real account or tax numbers, and no scanned personal documents.

Run from this folder (or through `mise demo-library`):

    uv run --with fpdf2 generate.py [--out DIR] [--fresh]

PDFs are written into DIR (default: the `library` folder beside this script),
replacing only the generated `*.pdf` files there. `--fresh` also deletes the
library's `.npn` folder, giving a blank slate — the journal in `.npn` holds the
curated titles, correspondents, tags and dates, so regenerating the PDF bytes
without `--fresh` leaves the old metadata pointing at hashes that no longer
exist.

The documents deliberately look like everyday paperwork (bills, statements,
receipts, contracts) so the library, search, filter and detail screenshots show
a lived-in library. Each has a real text layer, so full-text search finds them
without OCR.
"""

from __future__ import annotations

import argparse
import shutil
import sys
from pathlib import Path

from fpdf import FPDF

FONTS = Path("/usr/share/fonts/truetype/liberation")

CUSTOMER = ("Alex Morgan", "Lindenlaan 42", "1234 AB Riverton", "Netherlands")
CUSTOMER_ACCOUNT = "customer number 4-402-1173"
DASH = "–"  # en dash; Liberation Sans covers it

PAGE_W = 210
MARGIN = 18
BODY_SIZE = 10.5
LINE_H = 5.4


class Doc(FPDF):
    """One document, with the fictional sender in the running footer."""

    def __init__(self, sender: str, accent: tuple[int, int, int], *args, **kwargs):
        self.sender = sender
        self.accent = accent
        super().__init__(*args, **kwargs)

    def footer(self):
        self.set_y(-14)
        self.set_font("sans", "", 7.5)
        self.set_text_color(128, 128, 128)
        self.cell(0, 5, self.sender, align="C")


def start_doc(sender: str, accent: tuple[int, int, int]) -> Doc:
    pdf = Doc(sender, accent, format="A4")
    pdf.add_font("sans", "", str(FONTS / "LiberationSans-Regular.ttf"))
    pdf.add_font("sans", "B", str(FONTS / "LiberationSans-Bold.ttf"))
    pdf.add_font("sans", "I", str(FONTS / "LiberationSans-Italic.ttf"))
    pdf.set_margins(MARGIN, MARGIN, MARGIN)
    pdf.set_auto_page_break(True, margin=22)
    pdf.add_page()
    return pdf


def letterhead(pdf: Doc, company: str, contact: str) -> None:
    """Colour band with the company name; the accent colour makes thumbnails differ."""
    r, g, b = pdf.accent
    pdf.set_fill_color(r, g, b)
    pdf.rect(0, 0, PAGE_W, 26, style="F")
    pdf.set_xy(MARGIN, 8)
    pdf.set_font("sans", "B", 17)
    pdf.set_text_color(255, 255, 255)
    pdf.cell(0, 8, company)
    pdf.set_xy(MARGIN, 16.5)
    pdf.set_font("sans", "", 8.5)
    pdf.cell(0, 5, contact.split("  ·  ")[0])
    pdf.set_xy(MARGIN, 30)
    pdf.set_font("sans", "", 8.5)
    pdf.set_text_color(110, 110, 110)
    pdf.multi_cell(0, 4.2, contact)
    pdf.ln(4)


def to_customer(pdf: Doc, attn: str = "to") -> None:
    pdf.set_font("sans", "B", BODY_SIZE)
    pdf.set_text_color(60, 60, 60)
    pdf.cell(0, LINE_H, f"{attn.capitalize()} {CUSTOMER[0]}")
    pdf.ln(LINE_H)
    pdf.set_font("sans", "", BODY_SIZE)
    for line in CUSTOMER[1:]:
        pdf.cell(0, LINE_H, line)
        pdf.ln(LINE_H)
    pdf.ln(2)


def meta_row(pdf: Doc, doc_kind: str, number: str, date: str) -> None:
    pdf.set_font("sans", "", BODY_SIZE)
    pdf.set_text_color(90, 90, 90)
    pdf.cell(0, LINE_H, f"{doc_kind} no. {number}   {DASH}   {date}   {DASH}   {CUSTOMER_ACCOUNT}")
    pdf.ln(LINE_H + 3)


def heading(pdf: Doc, text: str) -> None:
    r, g, b = pdf.accent
    pdf.set_font("sans", "B", 13)
    pdf.set_text_color(r, g, b)
    pdf.cell(0, 7.5, text, new_x="LMARGIN", new_y="NEXT")
    pdf.ln(1.5)


def para(pdf: Doc, text: str, size: float = BODY_SIZE, italic: bool = False) -> None:
    pdf.set_font("sans", "I" if italic else "", size)
    pdf.set_text_color(40, 40, 40)
    pdf.multi_cell(0, size * 1.9, text)
    pdf.ln(2)


def table(pdf: Doc, columns: list[str], widths: list[float], rows: list[list[str]],
          aligns: list[str] | None = None) -> None:
    aligns = aligns or ["L"] * len(columns)
    pdf.set_font("sans", "B", BODY_SIZE)
    pdf.set_text_color(60, 60, 60)
    pdf.set_fill_color(238, 238, 238)
    for text, width, align in zip(columns, widths, aligns):
        pdf.cell(width, 6.6, text, border=1, align=align, fill=True)
    pdf.ln(6.6)
    pdf.set_font("sans", "", BODY_SIZE)
    pdf.set_text_color(40, 40, 40)
    for row in rows:
        for text, width, align in zip(row, widths, aligns):
            pdf.cell(width, 6.6, text, border=1, align=align)
        pdf.ln(6.6)
    pdf.ln(2)


def total_row(pdf: Doc, label: str, value: str) -> None:
    pdf.set_font("sans", "B", 11.5)
    pdf.set_text_color(40, 40, 40)
    pdf.set_x(-MARGIN - 90)
    pdf.cell(58, 8, label, border=1)
    pdf.cell(32, 8, value, border=1, align="R")
    pdf.ln(10)


def note(pdf: Doc, text: str) -> None:
    pdf.set_font("sans", "I", 9)
    pdf.set_text_color(110, 110, 110)
    pdf.multi_cell(0, 4.6, text)
    pdf.ln(1)


def sign_block(pdf: Doc, place: str, name: str, role: str) -> None:
    pdf.ln(10)
    pdf.set_font("sans", "", BODY_SIZE)
    pdf.set_text_color(40, 40, 40)
    pdf.cell(0, LINE_H, f"{place}, {name}")
    pdf.ln(LINE_H * 3)
    pdf.set_font("sans", "I", 9)
    pdf.cell(0, LINE_H, f"{role}")
    pdf.ln(LINE_H)


NOVA = ("Nova Energy B.V.  ·  Energieweg 8, 1234 AB Riverton  ·  +31 20 555 0110  ·  billing@nova-energy.example",
        (232, 114, 12))
NOVA_REG = "Nova Energy B.V. · fictional company · all names, addresses and numbers on this page are invented"


def bill(path: Path, company: str, accent: tuple[int, int, int], contact: str, reg: str,
          kind: str, number: str, date: str, title: str, intro: str,
          columns: list[str], widths: list[float], rows: list[list[str]], total: str,
          extra: str | None = None) -> None:
    pdf = start_doc(company, accent)
    letterhead(pdf, company, contact)
    to_customer(pdf)
    meta_row(pdf, kind, number, date)
    heading(pdf, title)
    para(pdf, intro)
    table(pdf, columns, widths, rows, aligns=["L", "R", "R", "R"][: len(columns)])
    total_row(pdf, "Total due", total)
    para(pdf, extra or "Please pay by direct debit; the amount will be collected automatically. "
                       "Questions about this " + kind.lower() + "? Reply to this address or call us on weekdays "
                       "between 8:30 and 17:30.")
    note(pdf, reg)
    pdf.output(path)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", default=str(Path(__file__).parent / "library"), help="output library folder")
    parser.add_argument("--fresh", action="store_true", help="also wipe .npn (loses curated metadata)")
    args = parser.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    for pdf_path in out.glob("*.pdf"):
        pdf_path.unlink()
    if args.fresh:
        shutil.rmtree(out / ".npn", ignore_errors=True)

    # — Nova Energy: two utility bills ——————————————————————————————————————
    bill(out / f"Electricity bill {DASH} October 2026.pdf", "Nova Energy", NOVA[1], NOVA[0],
         NOVA_REG, "Bill", "NE-E-2026-0841", "5 November 2026",
         "Electricity bill for October 2026",
         "Here is your monthly electricity bill. The meter readings below are estimates, "
         "based on your usage pattern of the past year.",
         ["Period", "Meter reading", "Usage", "Amount"],
         [46, 44, 40, 40],
         [["1 {0} 31 October 2026".format(DASH), "18 402 kWh", "212 kWh", "€ 61,15"],
          ["Standing charge", DASH, DASH, "€ 12,40"],
          ["Energy tax and surcharges", DASH, DASH, "€ 11,05"]],
         "€ 84,60")

    bill(out / f"Gas bill {DASH} October 2026.pdf", "Nova Energy", NOVA[1], NOVA[0],
         NOVA_REG, "Bill", "NE-G-2026-0842", "5 November 2026",
         "Gas bill for October 2026",
         "Your gas bill for October follows below. Colder nights are ahead: we expect your "
         "usage to rise until February and drop again in spring.",
         ["Period", "Meter reading", "Usage", "Amount"],
         [46, 44, 40, 40],
         [["1 {0} 31 October 2026".format(DASH), "2 118 m³", "26 m³", "€ 92,55"],
          ["Standing charge", DASH, DASH, "€ 12,40"],
          ["Energy tax and surcharges", DASH, DASH, "€ 7,40"]],
         "€ 112,35")

    # — AquaNet: water and sewer ———————————————————————————————————————————
    bill(out / f"Water and sewer {DASH} Q3 2026.pdf", "AquaNet Water", (30, 111, 184),
         "AquaNet Water N.V.  ·  Waterscheidingslaan 3, 1234 AA Riverton  ·  +31 20 555 0122  ·  klantenservice@aquanet.example",
         "AquaNet Water N.V. · fictional company · all names, addresses and numbers on this page are invented",
         "Bill", "AQ-2026-Q3-7113", "28 October 2026",
         "Water and sewer bill, July to September 2026",
         "This bill covers your drinking water supply and sewer connection for the third "
         "quarter of 2026.",
         ["Component", "Consumption", "Rate", "Amount"],
         [58, 34, 34, 44],
         [["Drinking water", "18 m³", "€ 1,27 / m³", "€ 22,86"],
          ["Sewer charge", "18 m³", "€ 0,89 / m³", "€ 16,02"],
          ["Fixed connection fee", DASH, DASH, "€ 22,92"]],
         "€ 61,80")

    # — Fibreline: internet ——————————————————————————————————————————————————
    bill(out / f"Internet invoice {DASH} October 2026.pdf", "Fibreline", (108, 74, 176),
         "Fibreline B.V.  ·  Glaslaan 21, 1234 AB Riverton  ·  +31 20 555 0144  ·  support@fibreline.example",
         "Fibreline B.V. · fictional company · all names, addresses and numbers on this page are invented",
         "Invoice", "FL-2026-10-0009", "1 November 2026",
         "Invoice for your Fibre 500 subscription",
         "Thanks for staying with Fibreline. This invoice covers October 2026: your fibre "
         "connection, the included Wi-Fi 6 router and unlimited data.",
         ["Item", "Period", "Amount"],
         [76, 56, 38],
         [["Fibre 500 subscription", "October 2026", "€ 42,50"],
          ["Router service and warranty", "October 2026", "€ 4,45"],
          ["Loyalty bonus {0} 12 months".format(DASH), DASH, "−€ 3,00"]],
         "€ 49,95")

    # — StreamBox: subscription ————————————————————————————————————————————
    bill(out / f"Streaming subscription {DASH} October 2026.pdf", "StreamBox", (179, 38, 30),
         "StreamBox Media Ltd.  ·  Broadcast Square 4, 1234 AB Riverton  ·  hello@streambox.example",
         "StreamBox Media Ltd. · fictional company · all names, addresses and numbers on this page are invented",
         "Invoice", "SB-INV-2026-10-991", "3 November 2026",
         "Your StreamBox Standard plan, October 2026",
         "This invoice was generated automatically. Nothing is due: your payment method on "
         "file was charged on 3 November 2026.",
         ["Item", "VAT", "Amount"],
         [76, 56, 38],
         [["StreamBox Standard, monthly", "9% (incl.)", "€ 13,99"],
          ["Second screen add-on", "9% (incl.)", "€ 3,00"],
          ["Loyalty credit", DASH, "−€ 3,00"]],
         "€ 13,99")

    # — Meridian Health: renewal + reimbursement ————————————————————————————
    pdf = start_doc("Meridian Health Insurance", (14, 124, 123))
    letterhead(pdf, "Meridian Health Insurance",
               "Meridian Health Insurance N.V.  ·  Zilverstraat 90, 1234 AB Riverton  ·  +31 20 555 0161  ·  members@meridian.example")
    to_customer(pdf)
    meta_row(pdf, "Letter", "MH-2026-4412", "2 November 2026")
    heading(pdf, "Your health insurance for 2027")
    para(pdf, "Your Meridian Basis insurance runs until 31 December 2026 and renews automatically. "
              "Here is what changes next year and what you will pay, so you can decide calmly whether "
              "to stay with us, switch to another Meridian plan, or move to a different insurer before "
              "1 January 2027.")
    para(pdf, "The premium below includes the standard government allowance of € 123,00 per month. "
              "Your deductible stays at the legal minimum of € 385,00.")
    table(pdf, ["Coverage", "Per month 2026", "Per month 2027"],
          [64, 50, 50],
          [["Meridian Basis (GP, hospital, pharmacy)", "€ 149,50", "€ 154,95"],
           ["Meridian Dental add-on", "€ 21,50", "€ 22,40"],
           ["Meridian Physio add-on (9 sessions)", "€ 9,95", "€ 10,55"]],
          aligns=["L", "R", "R"])
    total_row(pdf, "Total per month 2027", "€ 187,90")
    para(pdf, "Switching is easy: tell us before 31 December and we handle the paperwork with your "
              "new insurer. Staying requires nothing at all. Thank you for another year of trust.")
    sign_block(pdf, "Riverton, 2 November 2026", "S. Whitfield", "Head of Member Services")
    note(pdf, "Meridian Health Insurance N.V. · fictional company · all names, addresses and numbers on this page are invented")
    pdf.output(out / f"Health insurance renewal 2027.pdf")

    pdf = start_doc("Meridian Health Insurance", (14, 124, 123))
    letterhead(pdf, "Meridian Health Insurance",
               "Meridian Health Insurance N.V.  ·  Zilverstraat 90, 1234 AB Riverton  ·  +31 20 555 0161  ·  members@meridian.example")
    to_customer(pdf)
    meta_row(pdf, "Statement", "MH-DECL-2026-0912", "21 October 2026")
    heading(pdf, "Reimbursement: physiotherapy, September and October")
    para(pdf, "Good news: we have reimbursed the physiotherapy sessions from your Meridian Physio "
              "add-on. The amount below was transferred to your account on 21 October 2026.")
    table(pdf, ["Treatment", "Date", "Declared", "Reimbursed"],
          [62, 40, 34, 34],
          [["Physiotherapy, session 4 of 9", "24 September 2026", "€ 22,50", "€ 22,50"],
           ["Physiotherapy, session 5 of 9", "8 October 2026", "€ 22,50", "€ 22,50"],
           ["Physiotherapy, session 6 of 9", "15 October 2026", "€ 22,50", "€ 22,50"],
           ["Admin fee", DASH, "−€ 3,00", "€ 3,00"]],
          aligns=["L", "L", "R", "R"])
    total_row(pdf, "Transferred to your account", "€ 146,50")
    para(pdf, "Sessions 7 to 9 are still available this year. Ask your physiotherapist to declare "
              "them before 31 December 2026.")
    note(pdf, "Meridian Health Insurance N.V. · fictional company · all names, addresses and numbers on this page are invented")
    pdf.output(out / f"Reimbursement {DASH} physiotherapy.pdf")

    # — Sentinel Auto: car insurance renewal ————————————————————————————————
    pdf = start_doc("Sentinel Auto Insurance", (39, 68, 114))
    letterhead(pdf, "Sentinel Auto Insurance",
               "Sentinel Auto B.V.  ·  Vlietweg 17, 1234 AB Riverton  ·  +31 20 555 0180  ·  policy@sentinel.example")
    to_customer(pdf)
    meta_row(pdf, "Letter", "SA-POL-88-2026", "30 October 2026")
    heading(pdf, "Renewal of your car insurance policy")
    para(pdf, "Your all-risk policy for the Volvo V60 (registration XK-73-B, fictional) renews on "
              "1 December 2026. We have compared your premium with the market again; the table "
              "shows what you will pay next year.")
    table(pdf, ["Coverage", "Per month 2026", "Per month 2027"],
          [64, 50, 50],
          [["All-risk, including glass and vandalism", "€ 41,75", "€ 43,20"],
           ["Legal expenses add-on", "€ 4,50", "€ 4,50"],
           ["No-claim discount (11 years without damage)", "−€ 9,05", "−€ 9,50"]],
          aligns=["L", "R", "R"])
    total_row(pdf, "Total per month 2027", "€ 38,20")
    para(pdf, "Your no-claim record is excellent: with 11 damage-free years you keep our largest "
              "discount. Claim-free driving in 2027 earns you one step more.")
    sign_block(pdf, "Riverton, 30 October 2026", "M. Okafor", "Policies Department")
    note(pdf, "Sentinel Auto B.V. · fictional company · all names, addresses and numbers on this page are invented")
    pdf.output(out / "Car insurance renewal.pdf")

    # — Oakfield Bank: account statement + mortgage overview ————————————————
    pdf = start_doc("Oakfield Bank", (78, 107, 55))
    letterhead(pdf, "Oakfield Bank",
               "Oakfield Bank N.V.  ·  Eikenplein 5, 1234 AB Riverton  ·  +31 20 555 0199  ·  service@oakfield.example")
    to_customer(pdf)
    meta_row(pdf, "Statement", "OB-Q3-2026", "18 October 2026")
    heading(pdf, "Account statement, third quarter 2026")
    para(pdf, "An overview of every transaction on your Day-to-Day account (NL24 FAKE 0000 0000) "
              "between 1 July and 30 September 2026.")
    table(pdf, ["Date", "Description", "Amount"],
          [30, 106, 34],
          [["3 July", "Salary {0} Northvale Engineering".format(DASH), "€ 3.115,00"],
           ["4 July", "Rent {0} Bergman Rentals".format(DASH), "−€ 985,00"],
           ["6 July", "Groceries {0} daily".format(DASH), "−€ 74,20"],
           ["10 July", "Nova Energy direct debit", "−€ 84,60"],
           ["15 July", "AquaNet Water direct debit", "−€ 61,80"],
           ["1 August", "Salary {0} Northvale Engineering".format(DASH), "€ 3.115,00"],
           ["4 August", "Rent {0} Bergman Rentals".format(DASH), "−€ 985,00"],
           ["12 August", "Volt & Bolt Electronics", "−€ 1.299,00"],
           ["28 August", "Transfer to savings", "−€ 400,00"],
           ["1 September", "Salary {0} Northvale Engineering".format(DASH), "€ 3.115,00"],
           ["4 September", "Rent {0} Bergman Rentals".format(DASH), "−€ 985,00"],
           ["17 September", "GreenLeaf Garden Center", "−€ 239,95"],
           ["24 September", "Physiotherapy {0} Riverton".format(DASH), "−€ 22,50"],
           ["30 September", "Interest", "€ 1,12"]],
          aligns=["L", "L", "R"])
    total_row(pdf, "Closing balance 30 September", "€ 4.289,42")
    note(pdf, "Oakfield Bank N.V. · fictional company · all names, addresses and numbers on this page are invented")
    pdf.output(out / f"Account statement {DASH} Q3 2026.pdf")

    pdf = start_doc("Oakfield Bank", (78, 107, 55))
    letterhead(pdf, "Oakfield Bank",
               "Oakfield Bank N.V.  ·  Eikenplein 5, 1234 AB Riverton  ·  +31 20 555 0199  ·  mortgages@oakfield.example")
    to_customer(pdf)
    meta_row(pdf, "Statement", "OB-MTG-2025", "31 January 2026")
    heading(pdf, "Mortgage annual overview 2025")
    para(pdf, "This overview states what you paid in 2025 on your mortgage for Lindenlaan 42, "
              "Riverton. You need it for your income tax return.")
    table(pdf, ["Component", "2025"],
          [110, 60],
          [["Interest paid, annuity mortgage", "€ 6.142,80"],
           ["Principal repaid", "€ 4.987,90"],
           ["Outstanding balance at 31 December 2025", "€ 211.432,60"],
           ["Mortgage rate (fixed until 2032)", "3,7%"],
           ["Monthly payment", "€ 927,56"]],
          aligns=["L", "R"])
    para(pdf, "The interest above may be deductible if the loan meets the conditions of your "
              "mortgage agreement. Oakfield Bank reports this overview to the National Revenue "
              "Office automatically.")
    note(pdf, "Oakfield Bank N.V. · fictional company · all names, addresses and numbers on this page are invented")
    pdf.output(out / "Mortgage overview 2025.pdf")

    # — City of Riverton: property tax —————————————————————————————————————
    pdf = start_doc("City of Riverton", (74, 74, 74))
    letterhead(pdf, "City of Riverton",
               "Municipality of Riverton  ·  Town Hall, Marktplein 1, 1234 AB Riverton  ·  +31 20 555 0155  ·  taxes@riverton.example")
    to_customer(pdf, attn="attention of")
    meta_row(pdf, "Notice", "RV-OZB-2026-0428", "15 September 2026")
    heading(pdf, f"Property tax assessment (OZB) 2026 {DASH} Lindenlaan 42")
    para(pdf, "In 2026 you owe municipal property tax for your home at Lindenlaan 42. The assessed "
              "value (WOZ) of the property is € 348,000 and forms the basis for this assessment.")
    table(pdf, ["Component", "Rate", "Amount"],
          [70, 50, 50],
          [["Property tax (OZB), owner", "0,0681% of WOZ", "€ 237,00"],
           ["Sewer levy", "fixed", "€ 149,00"],
           ["Waste collection levy", "fixed", "€ 100,00"]],
          aligns=["L", "R", "R"])
    total_row(pdf, "Total 2026", "€ 486,00")
    para(pdf, "You may object to this assessment within six weeks. Pay in one go before 31 October "
              "2026, or in monthly instalments from October to July by direct debit.")
    sign_block(pdf, "Riverton, 15 September 2026", "The Municipal Executive", "Assessment Section")
    note(pdf, "Municipality of Riverton · fictional municipality · all names, addresses and numbers on this page are invented")
    pdf.output(out / "Property tax assessment 2026.pdf")

    # — National Revenue Office ————————————————————————————————————————————
    pdf = start_doc("National Revenue Office", (122, 46, 46))
    letterhead(pdf, "National Revenue Office",
               "National Revenue Office  ·  Fiscaalplein 12, 1234 AB Riverton  ·  +31 20 555 0177  ·  assessment@nro.example")
    to_customer(pdf, attn="attention of")
    meta_row(pdf, "Notice", "NRO-IB-2025-08823", "12 September 2026")
    heading(pdf, f"Final assessment income tax 2025 {DASH} notice")
    para(pdf, "We have finished checking your income tax return for 2025. The outcome is a refund, "
              "which we transferred to your bank account on 12 September 2026.")
    table(pdf, ["Component", "Amount"],
          [110, 60],
          [["Income tax owed before deductions", "€ 5.914,00"],
           ["Mortgage interest deduction", "−€ 2.116,47"],
           ["General tax credit", "−€ 254,71"],
           ["Labour tax credit", "−€ 980,26"]],
          aligns=["L", "R"])
    total_row(pdf, "Refund to your account", "€ 213,44")
    para(pdf, "This notice is based on the return you filed and the data your employer and bank "
              "reported to us. If you disagree, object within six weeks of the date on this notice.")
    sign_block(pdf, "Riverton, 12 September 2026", "D. Vermeer", "Assessment Officer")
    note(pdf, "National Revenue Office · fictional authority · all names, addresses and numbers on this page are invented")
    pdf.output(out / "Tax assessment notice 2025.pdf")

    # — Receipts ————————————————————————————————————————————————————————————
    bill(out / f"Receipt {DASH} laptop.pdf", "Volt & Bolt Electronics", (230, 168, 23),
         "Volt & Bolt Electronics  ·  Schakelstraat 55, 1234 AB Riverton  ·  +31 20 555 0135  ·  service@voltbolt.example",
         "Volt & Bolt Electronics B.V. · fictional company · all names, addresses and numbers on this page are invented",
         "Receipt", "VB-2026-09183", "12 August 2026",
         "Thank you for your purchase",
         "Receipt for your purchase at our Riverton store. Keep it for warranty claims.",
         ["Item", "Warranty", "Amount"],
         [76, 56, 38],
         [["Aurora ProBook 14 laptop, 16 GB, 512 GB SSD", "2 years", "€ 1.199,00"],
          ["USB-C hub, 7 ports", "2 years", "€ 59,00"],
          ["Extended warranty, 3rd year", DASH, "€ 41,00"]],
         "€ 1.299,00")

    bill(out / f"Receipt {DASH} garden furniture.pdf", "GreenLeaf Garden Center", (62, 124, 58),
         "GreenLeaf Garden Center  ·  Tuinbouwlane 9, 1234 AB Riverton  ·  +31 20 555 0166  ·  hallo@greenleaf.example",
         "GreenLeaf Garden Center B.V. · fictional company · all names, addresses and numbers on this page are invented",
         "Receipt", "GL-2026-04471", "17 September 2026",
         "Receipt for your garden set",
         "Thanks for shopping at GreenLeaf. Your new garden furniture is covered against "
         "manufacturing faults for two years.",
         ["Item", "Warranty", "Amount"],
         [76, 56, 38],
         [["Acacia wood bench, 2-seat", "2 years", "€ 149,00"],
          ["Cushion set, bench", "1 year", "€ 39,95"],
          ["Plant box with herbs", "none", "€ 51,00"]],
         "€ 239,95")

    # — Harmony Music School ———————————————————————————————————————————————
    pdf = start_doc("Harmony Music School", (138, 90, 43))
    letterhead(pdf, "Harmony Music School",
               "Harmony Music School  ·  Notenlaan 8, 1234 AB Riverton  ·  +31 20 555 0171  ·  inschrijving@harmony.example")
    to_customer(pdf)
    meta_row(pdf, "Confirmation", "HM-2026-0381", "25 September 2026")
    heading(pdf, "Enrolment confirmed: piano lessons, school year 2026{0}2027".format(DASH))
    para(pdf, "We are happy to confirm Emma Morgan's enrolment for piano lessons with teacher "
              "Clara Bennink. The school year runs from 6 September 2026 to 4 July 2027.")
    table(pdf, ["Detail", ""],
          [70, 100],
          [["Pupil", "Emma Morgan (age 10)"],
           ["Teacher", "Clara Bennink"],
           ["Day and time", "Wednesdays, 16:30{0}17:15".format(DASH)],
           ["Location", "Harmony Music School, room 3"],
           ["Lessons per year", "36, skipping school holidays"],
           ["Fee", "€ 25,00 per lesson, billed per term"]],
          aligns=["L", "L"])
    para(pdf, "Please cancel a lesson at least 24 hours in advance; shorter notice counts as a "
              "given lesson. The recital for family and friends is on Saturday 26 June 2027.")
    sign_block(pdf, "Riverton, 25 September 2026", "P. Vos", "Administration")
    note(pdf, "Harmony Music School · fictional school · all names, addresses and numbers on this page are invented")
    pdf.output(out / "Piano lessons {0} enrolment.pdf".format(DASH))

    # — Rental agreement ———————————————————————————————————————————————————
    pdf = start_doc("Bergman Rentals", (92, 122, 153))
    letterhead(pdf, "Bergman Rentals",
               "Bergman Rentals  ·  Vastgoedkade 11, 1234 AB Riverton  ·  +31 20 555 0102  ·  beheer@bergman.example")
    meta_row(pdf, "Contract", "BR-2026-L42", "1 August 2026")
    heading(pdf, f"Rental agreement {DASH} Lindenlaan 42, Riverton")
    para(pdf, "This agreement is between Bergman Rentals (the landlord) and Alex Morgan (the "
              "tenant), for the furnished two-bedroom home at Lindenlaan 42, 1234 AB Riverton.")
    for clause, title, body in [
        ("1", "Duration and rent", "The agreement starts on 1 September 2026 and runs for an "
                                   "indefinite period. The rent is € 985,00 per month, excluding "
                                   "a € 95,00 advance for gas, electricity and water, and is due "
                                   "on the first day of each month."),
        ("2", "Deposit", "The tenant has paid a deposit of two months' rent (€ 1.970,00). It will "
                         "be returned within one month after the end of the tenancy, minus any "
                         "costs for damage or arrears."),
        ("3", "Maintenance", "The landlord keeps the premises, heating installation and large "
                             "appliances in good order. The tenant reports defects promptly and "
                             "takes care of small repairs and the garden."),
        ("4", "Notice", "Both parties may end the agreement with one calendar month's notice, "
                        "in writing, after the first year."),
        ("5", "House rules", "Subletting is not allowed without written consent. One small pet "
                             "is permitted."),
    ]:
        pdf.set_font("sans", "B", BODY_SIZE)
        pdf.set_text_color(40, 40, 40)
        pdf.cell(0, LINE_H, f"Clause {clause}. {title}")
        pdf.ln(LINE_H)
        para(pdf, body)
    para(pdf, "Signed in Riverton on 1 August 2026, in duplicate.", italic=True)
    sign_block(pdf, "", "R. Bergman {0} landlord".format(DASH), "A. Morgan {0} tenant".format(DASH))
    note(pdf, "Bergman Rentals · fictional landlord · all names, addresses and numbers on this page are invented")
    pdf.output(out / f"Rental agreement {DASH} Lindenlaan 42.pdf")

    # — Aurora Appliances: warranty —————————————————————————————————————————
    pdf = start_doc("Aurora Appliances", (123, 45, 139))
    letterhead(pdf, "Aurora Appliances",
               "Aurora Appliances B.V.  ·  Apparatuurweg 200, 1234 AB Riverton  ·  +31 20 555 0190  ·  warranty@aurora.example")
    to_customer(pdf)
    meta_row(pdf, "Certificate", "AU-WTY-2026-33119", "22 August 2026")
    heading(pdf, f"Warranty certificate {DASH} Aurora SilentClean dishwasher")
    para(pdf, "This certificate extends the standard two-year manufacturer warranty on your "
              "Aurora SilentClean 60 dishwasher (serial fictional AUR-60-992-0471) to five years "
              "from the date of purchase.")
    table(pdf, ["Coverage", "Until"],
          [110, 60],
          [["Parts and labour for manufacturing faults", "22 August 2031"],
           ["Pump and electronics", "22 August 2031"],
           ["Wear parts (filters, seals)", "22 August 2028"],
           ["Damage from improper installation", "not covered"]],
          aligns=["L", "L"])
    para(pdf, "Register repairs at warranty@aurora.example with this certificate and the receipt. "
              "A technician visits within three working days.")
    note(pdf, "Aurora Appliances B.V. · fictional company · all names, addresses and numbers on this page are invented")
    pdf.output(out / f"Warranty certificate {DASH} dishwasher.pdf")

    # — Dental invoice ———————————————————————————————————————————————————————
    pdf = start_doc("Dental practice Hazel Hart", (91, 143, 168))
    letterhead(pdf, "Dr. Hazel Hart, DDS",
               "Dental practice Hazel Hart  ·  Tandwielstraat 4, 1234 AB Riverton  ·  +31 20 555 0186  ·  frontdesk@harthart.example")
    to_customer(pdf)
    meta_row(pdf, "Invoice", "HH-2026-0234", "9 November 2026")
    heading(pdf, "Invoice for your check-up and cleaning")
    para(pdf, "Thanks for visiting the practice on 9 November 2026. Your dentist found no cavities; "
              "keep up the good work. We have declared the treatment below to your insurer where "
              "possible; the remaining amount is for you.")
    table(pdf, ["Treatment", "Code", "Amount"],
          [76, 46, 38],
          [["Periodic check-up", "C11", "€ 21,20"],
           ["Removing tartar, two quadrants", "M31", "€ 43,10"],
           ["Fluoride treatment", "F21", "€ 14,30"],
           ["Declared to Meridian Health", DASH, "−€ 12,60"]],
          aligns=["L", "L", "R"])
    total_row(pdf, "Due after insurer", "€ 66,00")
    para(pdf, "Please transfer the amount within 30 days, quoting the invoice number. See you in "
              "six months for your next check-up, around 9 May 2027.")
    sign_block(pdf, "Riverton, 9 November 2026", "H. Hart", "Dentist")
    note(pdf, "Dental practice Hazel Hart · fictional practice · all names, addresses and numbers on this page are invented")
    pdf.output(out / f"Dental invoice {DASH} check-up.pdf")

    return 0


if __name__ == "__main__":
    sys.exit(main())
