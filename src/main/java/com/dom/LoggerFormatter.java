package com.dom;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.DateFormat;
import java.util.Date;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

public class LoggerFormatter extends Formatter {
    private static final DateFormat df = DateFormat.getTimeInstance(DateFormat.MEDIUM);

    public LoggerFormatter() {
        super();
    }


    public String format(LogRecord record) {

        StringBuffer sb = new StringBuffer();

        sb.append(df.format(new Date(record.getMillis())));
        sb.append(" ");

        //String level = ((record.getLevel().getName().equals("FINEST")) || (record.getLevel().getName().equals("FINE"))) ? "DEBUG" : record.getLevel().getName();
        sb.append(String.format("%1$-8s", record.getLevel()));
        sb.append(" [" + record.getThreadID() + "] ");

        sb.append(String.format("%1$-8s", record.getLoggerName()) + " ");
        sb.append(record.getSourceMethodName() + "() ");
        sb.append(formatMessage(record));

        if (record.getThrown() != null) {
            sb.append(" " + record.getThrown().getMessage());
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            record.getThrown().printStackTrace(pw);
            sb.append("\n");
            sb.append(sw.toString());
        }

        sb.append("\n");

        return sb.toString();
    }
}
