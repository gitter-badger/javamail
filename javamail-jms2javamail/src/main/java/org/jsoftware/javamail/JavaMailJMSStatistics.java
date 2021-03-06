package org.jsoftware.javamail;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.ejb.Singleton;
import javax.ejb.Startup;
import javax.mail.Address;
import javax.mail.Header;
import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import javax.management.*;
import javax.management.openmbean.*;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.management.ManagementFactory;
import java.util.Date;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ejb bean collecting JavaMail usage statistics and expose them via JMX.
 * @author szalik
 */
@Singleton(name = "JavaMailJMSStatisticsLocal")
@Startup
public class JavaMailJMSStatistics extends NotificationBroadcasterSupport implements JavaMailJMSStatisticsLocal, DynamicMBean {
    private static final MBeanInfo M_BEAN_INFO;
    private static final CompositeType ROW_HEADER_TYPE, ROW_ADDR_TYPE, MAIL_INFO_TYPE;
    private static final TabularType TAB_ADDR_TYPE, TAB_HEADER_TYPE;
    private MBeanServer platformMBeanServer;
    private ObjectName objectName = null;
    private Date startDate;
    private MessageAndAddresses lastSuccessMessage, lastFailMessage;
    private final AtomicLong successCounter = new AtomicLong(0), failureCounter = new AtomicLong(0), seq = new AtomicLong(0);

    static {
        try {
            ROW_HEADER_TYPE = new CompositeType("MailHeaders", "Mail headers",
                    new String[]{"header-name", "header-value"},
                    new String[]{"Name", "Value"},
                    new OpenType[]{SimpleType.STRING, SimpleType.STRING}
            );
            ROW_ADDR_TYPE = new CompositeType("MailAddress", "Mail single address",
                new String[]{"addressType", "address"},
                new String[]{"Address type", "Email address"},
                new OpenType[]{SimpleType.STRING, SimpleType.STRING}
            );
            TAB_ADDR_TYPE = new TabularType("Addresses", "Mail addresses", ROW_ADDR_TYPE, new String[] {"addressType", "address"});
            TAB_HEADER_TYPE = new TabularType("Headers", "Mail headers", ROW_HEADER_TYPE, new String[] {"header-name"});
            MAIL_INFO_TYPE = new CompositeType("MailInfo", "Mail info",
                    new String[]{"messageId", "date", "subject", "toAddresses", "headers", "errorDescription"},
                    new String[]{"Message ID", "Sent date", "Message subject", "Table of addresses", "Message headers", "Error description if any"},
                    new OpenType[]{SimpleType.STRING, SimpleType.DATE, SimpleType.STRING, TAB_ADDR_TYPE, TAB_HEADER_TYPE, SimpleType.STRING}
            );


            M_BEAN_INFO = new MBeanInfo(JavaMailJMSStatistics.class.getName(), "JavaMailJMS statistics",
                    new MBeanAttributeInfo[] {
                            new OpenMBeanAttributeInfoSupport("statisticsCollectionStartDate", "Start date", SimpleType.DATE, true, false, false),
                            new OpenMBeanAttributeInfoSupport("lastSuccessfulMailInfo", "Last successful message send", MAIL_INFO_TYPE, true, false, false),
                            new OpenMBeanAttributeInfoSupport("lastFailureMailInfo", "Last unsuccessful message send", MAIL_INFO_TYPE, true, false, false),
                            new OpenMBeanAttributeInfoSupport("countSuccessful", "Successful messages counter", SimpleType.LONG, true, false, false),
                            new OpenMBeanAttributeInfoSupport("countFailure", "Unsuccessful messages counter", SimpleType.LONG, true, false, false)
                    },
                    new MBeanConstructorInfo[0],
                    new MBeanOperationInfo[] { new OpenMBeanOperationInfoSupport("reset", "Reset statistics", new OpenMBeanParameterInfo[0], SimpleType.DATE, MBeanOperationInfo.ACTION)},
                    new MBeanNotificationInfo[] { new MBeanNotificationInfo(new String[]{"JavaMail-Send-Success", "JavaMail-Send-Failure"}, "mail-events", "Info about emails that has been sent.") }
            );
        } catch (OpenDataException e) {
            throw new RuntimeException("Cannot create openTypes", e);
        }
    }


    @PostConstruct
    public void registerInJMX() {
        try {
            reset();
            objectName = new ObjectName("JavaMailJMS:type=" + getClass().getName());
            platformMBeanServer = ManagementFactory.getPlatformMBeanServer();
            platformMBeanServer.registerMBean(this, objectName);
        } catch (Exception e) {
            throw new IllegalStateException("Problem during registration of JavaMailStatistics into JMX:" + e, e);
        }
    }

    @PreDestroy
    public void unregisterFromJMX() {
        try {
            platformMBeanServer.unregisterMBean(this.objectName);
        } catch (Exception e) {
            throw new IllegalStateException("Problem during unregistration of JavaMailStatistics from JMX:" + e, e);
        }
    }

    public void onSuccess(MimeMessage mimeMessage, Address[] addresses) {
        MessageAndAddresses maa = new MessageAndAddresses(mimeMessage, addresses, null);
        successCounter.incrementAndGet();
        lastSuccessMessage = maa;
        sendNotification(maa);
    }

    public void onFailure(MimeMessage mimeMessage, Address[] addresses, Exception ex) {
        MessageAndAddresses maa = new MessageAndAddresses(mimeMessage, addresses, ex);
        failureCounter.incrementAndGet();
        lastFailMessage = maa;
        sendNotification(maa);
    }

    private void reset() {
        startDate = new Date();
        lastFailMessage = null;
        lastSuccessMessage = null;
        successCounter.set(0);
        failureCounter.set(0);
    }

    private void sendNotification(MessageAndAddresses maa) {
        try {
            Notification notification;
            if (maa.getException() == null) {
                notification = new Notification("JavaMail-Send-Success", maa.getMessage().getMessageID(), seq.incrementAndGet(), "Sent");
            } else {
                notification = new Notification("JavaMail-Send-Failure", maa.getMessage().getMessageID(), seq.incrementAndGet(), "Exception:" + maa.getException());
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Subject:").append(maa.getMessage().getSubject()).append('\n');
            for(Address address : maa.getAddresses()) {
                sb.append(address).append('\n');
            }
            notification.setUserData(sb.toString());
            sendNotification(notification);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private static CompositeData convert(MessageAndAddresses maa) {
        if (maa == null) {
            return null;
        }
        try {
            TabularData addrData = new TabularDataSupport(TAB_ADDR_TYPE);
            for(Address addr : maa.getAddresses()) {
                addrData.put(new CompositeDataSupport(ROW_ADDR_TYPE, new String[]{"addressType", "address"}, new Object[]{addr.getType(), addr.toString()}));
            }
            TabularData headerData = new TabularDataSupport(TAB_HEADER_TYPE);
            Enumeration en = maa.getMessage().getAllHeaders();
            while (en.hasMoreElements()) {
                Header header = (Header) en.nextElement();
                headerData.put(new CompositeDataSupport(ROW_HEADER_TYPE, new String[]{"header-name", "header-value"}, new Object[]{header.getName(), header.getValue()}));
            }
            String error = null;
            if (maa.getException() != null) {
                StringWriter sw = new StringWriter();
                sw.append(maa.getException().toString());
                maa.getException().printStackTrace(new PrintWriter(sw));
                sw.flush();
                error = sw.toString();
            }
            return new CompositeDataSupport(MAIL_INFO_TYPE,
                    new String[] {"messageId", "date", "subject", "toAddresses", "headers", "errorDescription"},
                    new Object[]{maa.getMessage().getMessageID(), new Date(maa.getTimestamp()), maa.getMessage().getSubject(), addrData, headerData, error}
            );
        } catch (OpenDataException e) {
            throw new RuntimeException(e);
        } catch (MessagingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public AttributeList getAttributes(String[] attributeNames) {
        AttributeList resultList = new AttributeList();
        if (attributeNames.length == 0) {
            return resultList;
        }
        for (int i = 0; i < attributeNames.length; i++) {
            try {
                Object value = getAttribute(attributeNames[i]);
                resultList.add(new Attribute(attributeNames[i], value));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return resultList;
    }

    @Override
    public Object getAttribute(String attribute) throws AttributeNotFoundException, MBeanException, ReflectionException {
        if ("statisticsCollectionStartDate".equalsIgnoreCase(attribute)) {
            return startDate;
        }
        if ("lastSuccessfulMailInfo".equalsIgnoreCase(attribute)) {
            return convert(lastSuccessMessage);
        }
        if ("lastFailureMailInfo".equalsIgnoreCase(attribute)) {
            return convert(lastFailMessage);
        }
        if ("countSuccessful".equalsIgnoreCase(attribute)) {
            return successCounter.get();
        }
        if ("countFailure".equalsIgnoreCase(attribute)) {
            return failureCounter.get();
        }
        throw new AttributeNotFoundException("Attribute " + attribute + " not found");
    }

    @Override
    public void setAttribute(Attribute attribute) throws AttributeNotFoundException, InvalidAttributeValueException, MBeanException, ReflectionException {
        throw new InvalidAttributeValueException("Attribute " + attribute + " is read-only.");
    }

    @Override
    public AttributeList setAttributes(AttributeList attributes) {
        for(Attribute attribute : attributes.asList()) {
            try {
                setAttribute(attribute);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return attributes;
    }




    @Override
    public Object invoke(String actionName, Object[] params, String[] signature) throws MBeanException, ReflectionException {
        if ("reset".equalsIgnoreCase(actionName)) {
            reset();
            return startDate;
        }
        return null;
    }

    @Override
    public MBeanInfo getMBeanInfo() {
        return M_BEAN_INFO;
    }
}


class MessageAndAddresses {
    private final MimeMessage message;
    private final Address[] addresses;
    private final Exception exception;
    private final long timestamp;

    MessageAndAddresses(MimeMessage message, Address[] addresses, Exception exception) {
        this.message = message;
        this.addresses = addresses;
        this.exception = exception;
        this.timestamp = System.currentTimeMillis();
    }

    public Exception getException() {
        return exception;
    }

    public MimeMessage getMessage() {
        return message;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public Address[] getAddresses() {
        return addresses;
    }
}