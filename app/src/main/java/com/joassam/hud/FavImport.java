package com.joassam.hud;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 엑셀(.xlsx) 또는 CSV에서 "이름, 주소" 두 칸을 읽어 자주 가는 곳으로 등록.
 * A열 = 이름, B열 = 주소. 첫 줄이 "이름/주소" 같은 제목이면 건너뜀.
 */
final class FavImport {

    private FavImport() {
    }

    /** 깨진 글자·제어문자가 섞였거나 너무 긴 값 */
    static boolean broken(String s) {
        if (s.length() > 120) return true;
        for (char c : s.toCharArray()) {
            if (c == '\uFFFD' || (c < 0x20 && c != '\t')) return true;
            // 한글·영문·숫자·흔한 기호 외의 한자 등이 섞이면 깨진 것으로 봄
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS) return true;
        }
        return false;
    }

    static List<String[]> parse(byte[] data, String fileName) throws Exception {
        // 옛 엑셀(.xls) 파일은 못 읽음 → 안내
        boolean ole = data.length > 8 && (data[0] & 0xff) == 0xD0 && (data[1] & 0xff) == 0xCF && (data[2] & 0xff) == 0x11 && (data[3] & 0xff) == 0xE0;
        boolean zip = data.length > 4 && data[0] == 'P' && data[1] == 'K';
        List<String[]> rows = ole ? xls(data) : zip ? xlsx(data) : csv(data);
        zip = zip || ole;
        List<String[]> out = new ArrayList<>();
        int bad = 0;
        for (String[] r : rows) for (String v : r) if (v != null && broken(v)) bad++;
        if (!zip && bad > rows.size() / 2 && bad > 2) throw new Exception("파일 글자가 깨져서 읽을 수 없어요. 엑셀 .xlsx 또는 CSV(UTF-8)로 저장해 주세요");
        for (int i = 0; i < rows.size(); i++) {
            String[] r = rows.get(i);
            String n = r.length > 0 && r[0] != null ? r[0].trim() : "";
            String a = r.length > 1 && r[1] != null ? r[1].trim() : "";
            if (n.isEmpty() || a.isEmpty()) continue;
            if (i == 0 && (n.contains("이름") || n.contains("장소") || a.contains("주소"))) continue; // 제목 줄
            if (broken(n) || broken(a)) continue;
            n = n.replace("=", " ").replace("\n", " ");
            a = a.replace("\n", " ");
            out.add(new String[]{n, a});
        }
        return out;
    }

    /** 기존 목록 + 새 목록 (같은 이름은 새 주소로 바꿈), "이름=주소" 줄 글로 */
    static String merge(String existing, List<String[]> add) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String line : existing.split("\n")) {
            int i = line.indexOf('=');
            if (i > 0) m.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
        }
        for (String[] r : add) m.put(r[0], r[1]);
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (b.length() > 0) b.append('\n');
            b.append(e.getKey()).append('=').append(e.getValue());
        }
        return b.toString();
    }

    // ───────── 옛 엑셀 .xls (BIFF8) ─────────
    private static int u16(byte[] b, int o) {
        return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8;
    }

    private static int i32(byte[] b, int o) {
        return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8 | (b[o + 2] & 0xff) << 16 | (b[o + 3] & 0xff) << 24;
    }

    /** OLE 묶음 파일에서 "Workbook" 내용 꺼내기 */
    private static byte[] oleStream(byte[] f, String want1, String want2) throws Exception {
        int secSize = 1 << u16(f, 0x1E);
        int miniSize = 1 << u16(f, 0x20);
        int nFat = i32(f, 0x2C);
        int dirStart = i32(f, 0x30);
        int cutoff = i32(f, 0x38);
        int miniFatStart = i32(f, 0x3C);
        int difatStart = i32(f, 0x44);
        // FAT 섹터 목록
        List<Integer> fatSecs = new ArrayList<>();
        for (int i = 0; i < 109 && fatSecs.size() < nFat; i++) fatSecs.add(i32(f, 0x4C + i * 4));
        int dif = difatStart;
        while (fatSecs.size() < nFat && dif >= 0 && dif != -2) {
            int off = 512 + dif * secSize;
            for (int i = 0; i < secSize / 4 - 1 && fatSecs.size() < nFat; i++) fatSecs.add(i32(f, off + i * 4));
            dif = i32(f, off + secSize - 4);
        }
        int per = secSize / 4;
        int[] fat = new int[fatSecs.size() * per];
        for (int i = 0; i < fatSecs.size(); i++) {
            int off = 512 + fatSecs.get(i) * secSize;
            for (int j = 0; j < per; j++) fat[i * per + j] = i32(f, off + j * 4);
        }
        byte[] dir = chain(f, fat, dirStart, secSize, -1);
        byte[] rootStream = null;
        int[] miniFat = null;
        for (int e = 0; e + 128 <= dir.length; e += 128) {
            int nameLen = u16(dir, e + 0x40);
            if (nameLen < 2) continue;
            String name = new String(dir, e, nameLen - 2, StandardCharsets.UTF_16LE);
            int type = dir[e + 0x42];
            int start = i32(dir, e + 0x74);
            int size = i32(dir, e + 0x78);
            if (type == 5) { // 루트: 작은 조각들 저장소
                rootStream = chain(f, fat, start, secSize, size);
                byte[] mf = miniFatStart >= 0 ? chain(f, fat, miniFatStart, secSize, -1) : new byte[0];
                miniFat = new int[mf.length / 4];
                for (int i = 0; i < miniFat.length; i++) miniFat[i] = i32(mf, i * 4);
            } else if (type == 2 && (name.equals(want1) || name.equals(want2))) {
                if (size >= cutoff || rootStream == null) return chain(f, fat, start, secSize, size);
                return chain(rootStream, miniFat, start, miniSize, size, true);
            }
        }
        throw new Exception("엑셀 내용을 찾지 못했어요");
    }

    private static byte[] chain(byte[] f, int[] fat, int start, int secSize, int size) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int s = start, guard = 0;
        while (s >= 0 && s < fat.length && guard++ < 1_000_000) {
            int off = 512 + s * secSize;
            if (off + secSize > f.length) break;
            o.write(f, off, secSize);
            s = fat[s];
        }
        byte[] r = o.toByteArray();
        return size >= 0 && size < r.length ? java.util.Arrays.copyOf(r, size) : r;
    }

    private static byte[] chain(byte[] mini, int[] miniFat, int start, int secSize, int size, boolean isMini) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int s = start, guard = 0;
        while (s >= 0 && s < miniFat.length && guard++ < 1_000_000) {
            int off = s * secSize;
            if (off + secSize > mini.length) break;
            o.write(mini, off, secSize);
            s = miniFat[s];
        }
        byte[] r = o.toByteArray();
        return size >= 0 && size < r.length ? java.util.Arrays.copyOf(r, size) : r;
    }

    /** 레코드 여러 개에 걸친 글자를 읽는 도우미 (CONTINUE 경계 처리) */
    private static final class Blocks {
        final List<byte[]> blocks;
        int bi = 0, pos = 0;

        Blocks(List<byte[]> b) {
            blocks = b;
        }

        boolean more() {
            while (bi < blocks.size() && pos >= blocks.get(bi).length) {
                bi++;
                pos = 0;
            }
            return bi < blocks.size();
        }

        int u8() {
            more();
            return blocks.get(bi)[pos++] & 0xff;
        }

        int u16() {
            return u8() | u8() << 8;
        }

        int i32() {
            return u16() | u16() << 16;
        }

        void skip(int n) {
            for (int i = 0; i < n && more(); i++) pos++;
        }

        String chars(int cch, boolean high) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < cch; i++) {
                if (bi < blocks.size() && pos >= blocks.get(bi).length) {
                    // 글자 중간에 레코드가 바뀌면 새 레코드 첫 바이트가 글자 크기 표시
                    bi++;
                    pos = 0;
                    if (bi >= blocks.size()) break;
                    high = (blocks.get(bi)[pos++] & 1) != 0;
                }
                if (bi >= blocks.size()) break;
                if (high) b.append((char) u16());
                else b.append((char) u8());
            }
            return b.toString();
        }

        String xlString() {
            int cch = u16();
            int g = u8();
            boolean high = (g & 1) != 0;
            int runs = (g & 8) != 0 ? u16() : 0;
            int ext = (g & 4) != 0 ? i32() : 0;
            String s = chars(cch, high);
            skip(runs * 4 + ext);
            return s;
        }
    }

    private static List<String[]> xls(byte[] data) throws Exception {
        byte[] wb = oleStream(data, "Workbook", "Book");
        List<String> sst = new ArrayList<>();
        java.util.TreeMap<Integer, String[]> rows = new java.util.TreeMap<>();
        int p = 0;
        boolean inSheet = false;
        while (p + 4 <= wb.length) {
            int id = u16(wb, p), len = u16(wb, p + 2);
            int d = p + 4;
            if (d + len > wb.length) break;
            if (id == 0x0809) { // BOF
                int dt = u16(wb, d + 2);
                if (dt == 0x0010) inSheet = true;
            } else if (id == 0x000A) { // EOF
                if (inSheet) break; // 첫 번째 시트만
            } else if (id == 0x00FC) { // SST (+ 뒤따르는 CONTINUE)
                List<byte[]> bl = new ArrayList<>();
                bl.add(java.util.Arrays.copyOfRange(wb, d, d + len));
                int q = d + len;
                while (q + 4 <= wb.length && u16(wb, q) == 0x003C) {
                    int l2 = u16(wb, q + 2);
                    bl.add(java.util.Arrays.copyOfRange(wb, q + 4, Math.min(wb.length, q + 4 + l2)));
                    q += 4 + l2;
                }
                Blocks r = new Blocks(bl);
                r.i32();
                int unique = r.i32();
                for (int i = 0; i < unique && r.more(); i++) sst.add(r.xlString());
                p = q;
                continue;
            } else if (inSheet && (id == 0x00FD || id == 0x0204 || id == 0x0203 || id == 0x027E)) {
                int row = u16(wb, d), col = u16(wb, d + 2);
                if (col <= 1) {
                    String v = null;
                    if (id == 0x00FD) {
                        int idx = i32(wb, d + 6);
                        v = idx >= 0 && idx < sst.size() ? sst.get(idx) : "";
                    } else if (id == 0x0204) {
                        List<byte[]> bl = new ArrayList<>();
                        bl.add(java.util.Arrays.copyOfRange(wb, d + 6, d + len));
                        v = new Blocks(bl).xlString();
                    } else if (id == 0x0203) {
                        double x = Double.longBitsToDouble(
                                ((long) i32(wb, d + 6) & 0xffffffffL) | ((long) i32(wb, d + 10)) << 32);
                        v = x == Math.rint(x) ? String.valueOf((long) x) : String.valueOf(x);
                    } else {
                        int rk = i32(wb, d + 6);
                        double x = (rk & 2) != 0 ? (rk >> 2) : Double.longBitsToDouble(((long) (rk & 0xfffffffc)) << 32);
                        if ((rk & 1) != 0) x /= 100;
                        v = x == Math.rint(x) ? String.valueOf((long) x) : String.valueOf(x);
                    }
                    String[] r = rows.computeIfAbsent(row, k -> new String[2]);
                    r[col] = v;
                }
            }
            p = d + len;
        }
        return new ArrayList<>(rows.values());
    }

    // ───────── CSV ─────────
    private static List<String[]> csv(byte[] data) {
        String s = new String(data, StandardCharsets.UTF_8);
        if (s.indexOf('�') >= 0) {
            try {
                s = new String(data, "MS949"); // 한글 엑셀이 저장한 CSV
            } catch (Exception ignored) {
            }
        }
        if (s.startsWith("﻿")) s = s.substring(1);
        List<String[]> rows = new ArrayList<>();
        for (String line : s.split("\r?\n")) {
            if (line.trim().isEmpty()) continue;
            rows.add(splitCsv(line));
        }
        return rows;
    }

    private static String[] splitCsv(String line) {
        List<String> f = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        char sep = line.indexOf('\t') >= 0 && line.indexOf(',') < 0 ? '\t' : ',';
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (q) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else if (c == '"') q = false;
                else cur.append(c);
            } else if (c == '"') q = true;
            else if (c == sep) {
                f.add(cur.toString());
                cur.setLength(0);
            } else cur.append(c);
        }
        f.add(cur.toString());
        return f.toArray(new String[0]);
    }

    // ───────── XLSX (첫 번째 시트) ─────────
    private static List<String[]> xlsx(byte[] data) throws Exception {
        String shared = null, sheet = null, firstSheetName = null;
        Map<String, String> sheets = new LinkedHashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                String n = e.getName();
                if (n.equals("xl/sharedStrings.xml")) shared = read(z);
                else if (n.startsWith("xl/worksheets/sheet") && n.endsWith(".xml")) sheets.put(n, read(z));
            }
        }
        if (sheets.isEmpty()) throw new Exception("엑셀 시트를 찾지 못했어요");
        sheet = sheets.containsKey("xl/worksheets/sheet1.xml") ? sheets.get("xl/worksheets/sheet1.xml") : sheets.values().iterator().next();

        List<String> strings = new ArrayList<>();
        if (shared != null) {
            Matcher si = Pattern.compile("<si>(.*?)</si>", Pattern.DOTALL).matcher(shared);
            Pattern t = Pattern.compile("<t[^>]*>(.*?)</t>", Pattern.DOTALL);
            while (si.find()) {
                Matcher tm = t.matcher(si.group(1));
                StringBuilder b = new StringBuilder();
                while (tm.find()) b.append(tm.group(1));
                strings.add(unescape(b.toString()));
            }
        }
        List<String[]> rows = new ArrayList<>();
        Matcher row = Pattern.compile("<row[^>]*>(.*?)</row>", Pattern.DOTALL).matcher(sheet);
        Pattern cell = Pattern.compile("<c\\s([^>]*?)(?:/>|>(.*?)</c>)", Pattern.DOTALL);
        Pattern ref = Pattern.compile("r=\"([A-Z]+)\\d+\"");
        Pattern typ = Pattern.compile("t=\"([a-zA-Z]+)\"");
        Pattern val = Pattern.compile("<v>(.*?)</v>", Pattern.DOTALL);
        Pattern inl = Pattern.compile("<t[^>]*>(.*?)</t>", Pattern.DOTALL);
        while (row.find()) {
            String[] r = new String[2];
            Matcher c = cell.matcher(row.group(1));
            int idx = 0;
            while (c.find()) {
                String attrs = c.group(1), body = c.group(2) == null ? "" : c.group(2);
                Matcher rm = ref.matcher(attrs);
                int col = rm.find() ? colIndex(rm.group(1)) : idx;
                idx = col + 1;
                if (col > 1) continue;
                Matcher tm = typ.matcher(attrs);
                String type = tm.find() ? tm.group(1) : "";
                String v;
                if (type.equals("inlineStr")) {
                    Matcher im = inl.matcher(body);
                    StringBuilder b = new StringBuilder();
                    while (im.find()) b.append(im.group(1));
                    v = unescape(b.toString());
                } else {
                    Matcher vm = val.matcher(body);
                    v = vm.find() ? vm.group(1) : "";
                    if (type.equals("s")) {
                        try {
                            v = strings.get(Integer.parseInt(v.trim()));
                        } catch (Exception ex) {
                            v = "";
                        }
                    } else {
                        v = unescape(v);
                    }
                }
                r[col] = v;
            }
            rows.add(r);
        }
        return rows;
    }

    private static int colIndex(String letters) {
        int n = 0;
        for (char ch : letters.toCharArray()) n = n * 26 + (ch - 'A' + 1);
        return n - 1;
    }

    private static String unescape(String s) {
        // &#51665; / &#xD558; 같은 숫자 표기도 글자로
        Matcher m = Pattern.compile("&#(x?)([0-9a-fA-F]+);").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            int cp;
            try {
                cp = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
            } catch (NumberFormatException e) {
                cp = '?';
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(cp))));
        }
        m.appendTail(sb);
        s = sb.toString();
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&");
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        return o.toString("UTF-8");
    }
}
