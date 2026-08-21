package com.example.workbench.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 模型回退答案护栏：纯函数集合，不依赖任何 RagService 实例状态。
 * 从 RagService 抽取，行为与原实现完全一致。
 */
class RagAnswerGuardrail {

    private static final Pattern REPEATED_CHARACTER = Pattern.compile("(?s).*(.)\\1{3,}.*");
    private static final Pattern REPEATED_SEQUENCE = Pattern.compile("(?s).*(.{2,4})\\1{2,}.*");
    private static final Pattern SUSPICIOUS_LATIN_TOKEN = Pattern.compile("(?i)(?<![a-z])[a-z]{8,}(?![a-z])");
    private static final Pattern MIXED_LANGUAGE_IDENTIFIER = Pattern.compile(
            "(?U)(?:[A-Za-z][A-Za-z0-9]*_[\\p{IsHan}]+(?:_[A-Za-z][A-Za-z0-9]*)?|[\\p{IsHan}]+_[A-Za-z][A-Za-z0-9_]*)");
    private static final Pattern HAN_CHARACTER = Pattern.compile("\\p{IsHan}");
    private static final Pattern SQL_STATEMENT = Pattern.compile(
            "(?is).*\\b(?:select|insert|update|delete|create|alter|drop|with)\\b.*");

    boolean isUsableModelFallbackAnswer(String question, String answer) {
        if (answer == null || answer.strip().length() < 12) {
            return false;
        }

        String normalized = answer.strip();
        if (normalized.indexOf('￾') >= 0 || normalized.chars().anyMatch(this::isUnsupportedControlCharacter)) {
            return false;
        }
        if (REPEATED_CHARACTER.matcher(normalized).matches() || REPEATED_SEQUENCE.matcher(normalized).matches()) {
            return false;
        }
        if (containsInvalidCodeIdentifier(question, normalized)) {
            return false;
        }
        if (asksForAllPostgresTables(question)
                && Pattern.compile("(?is)table_schema\\s*=\\s*['\"]public['\"]").matcher(normalized).find()) {
            return false;
        }

        var matcher = SUSPICIOUS_LATIN_TOKEN.matcher(normalized);
        while (matcher.find()) {
            if (isLikelyGibberishToken(matcher.group().toLowerCase())) {
                return false;
            }
        }
        return true;
    }

    private boolean asksForAllPostgresTables(String question) {
        if (question == null) {
            return false;
        }
        String normalized = question.toLowerCase(Locale.ROOT);
        return (normalized.contains("postgresql") || normalized.contains("pgsql") || normalized.contains("postgres"))
                && (normalized.contains("所有表") || normalized.contains("全部表"));
    }

    private boolean isUnsupportedControlCharacter(int character) {
        return Character.isISOControl(character) && character != '\n' && character != '\r' && character != '\t';
    }

    private boolean containsInvalidCodeIdentifier(String question, String answer) {
        ParsedCode parsed = parseCodeBlocks(answer);
        if (parsed.unclosedFence()) {
            return true;
        }

        boolean sqlFound = false;
        for (CodeBlock block : parsed.blocks()) {
            String language = block.language();
            String code = block.code();
            if (MIXED_LANGUAGE_IDENTIFIER.matcher(code).find()) {
                return true;
            }
            boolean sqlCode = language.equals("sql") || language.equals("postgresql") || language.equals("postgres")
                    || language.equals("pgsql") || SQL_STATEMENT.matcher(code).matches();
            sqlFound |= sqlCode;
            if (sqlCode && containsHanOutsideSqlText(code)) {
                return true;
            }
        }

        if (!asksForSql(question)) {
            return false;
        }
        if (!sqlFound && SQL_STATEMENT.matcher(answer).matches()) {
            sqlFound = true;
            if (containsHanOutsideSqlText(answer)) {
                return true;
            }
        }
        return !sqlFound;
    }

    private ParsedCode parseCodeBlocks(String answer) {
        List<CodeBlock> blocks = new ArrayList<>();
        String language = "";
        StringBuilder code = null;
        for (String line : answer.split("\\R", -1)) {
            String stripped = line.stripLeading();
            if (stripped.startsWith("```")) {
                if (code == null) {
                    String info = stripped.substring(3).strip();
                    language = info.isBlank() ? "" : info.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
                    code = new StringBuilder();
                } else {
                    blocks.add(new CodeBlock(language, code.toString()));
                    code = null;
                    language = "";
                }
                continue;
            }
            if (code != null) {
                if (!code.isEmpty()) {
                    code.append('\n');
                }
                code.append(line);
            }
        }
        if (code != null) {
            blocks.add(new CodeBlock(language, code.toString()));
        }
        return new ParsedCode(List.copyOf(blocks), code != null);
    }

    private boolean asksForSql(String question) {
        if (question == null) {
            return false;
        }
        String normalized = question.toLowerCase(Locale.ROOT);
        return normalized.contains("sql") || normalized.contains("查询语句") || normalized.contains("查询所有表")
                || normalized.contains("查询全部表");
    }

    private boolean containsHanOutsideSqlText(String sql) {
        String withoutComments = sql
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)--.*$", " ");
        String withoutQuotedText = withoutComments
                .replaceAll("'(?:''|[^'])*'", "''")
                .replaceAll("\"(?:\"\"|[^\"])*\"", "\"\"");
        return HAN_CHARACTER.matcher(withoutQuotedText).find();
    }

    private boolean isLikelyGibberishToken(String token) {
        // 长英文词在技术回答中很常见，不能靠固定白名单判断；只拦截字符种类极少的重复乱码串。
        long distinctCharacters = token.chars().distinct().count();
        return token.length() >= 9 && distinctCharacters <= 4;
    }

    private record CodeBlock(String language, String code) {
    }

    private record ParsedCode(List<CodeBlock> blocks, boolean unclosedFence) {
    }
}
