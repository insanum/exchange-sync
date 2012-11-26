package com.zerodes.exchangesync.exchange;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;

import microsoft.exchange.webservices.data.Appointment;
import microsoft.exchange.webservices.data.Attendee;
import microsoft.exchange.webservices.data.BasePropertySet;
import microsoft.exchange.webservices.data.BodyType;
import microsoft.exchange.webservices.data.CalendarView;
import microsoft.exchange.webservices.data.ConflictResolutionMode;
import microsoft.exchange.webservices.data.EmailAddress;
import microsoft.exchange.webservices.data.EmailMessage;
import microsoft.exchange.webservices.data.EventType;
import microsoft.exchange.webservices.data.ExchangeCredentials;
import microsoft.exchange.webservices.data.ExchangeService;
import microsoft.exchange.webservices.data.ExtendedProperty;
import microsoft.exchange.webservices.data.ExtendedPropertyDefinition;
import microsoft.exchange.webservices.data.FindFoldersResults;
import microsoft.exchange.webservices.data.FindItemsResults;
import microsoft.exchange.webservices.data.Folder;
import microsoft.exchange.webservices.data.FolderId;
import microsoft.exchange.webservices.data.FolderSchema;
import microsoft.exchange.webservices.data.FolderTraversal;
import microsoft.exchange.webservices.data.FolderView;
import microsoft.exchange.webservices.data.Item;
import microsoft.exchange.webservices.data.ItemEvent;
import microsoft.exchange.webservices.data.ItemId;
import microsoft.exchange.webservices.data.ItemView;
import microsoft.exchange.webservices.data.LogicalOperator;
import microsoft.exchange.webservices.data.MapiPropertyType;
import microsoft.exchange.webservices.data.MeetingRequest;
import microsoft.exchange.webservices.data.MessageBody;
import microsoft.exchange.webservices.data.NotificationEvent;
import microsoft.exchange.webservices.data.NotificationEventArgs;
import microsoft.exchange.webservices.data.PropertySet;
import microsoft.exchange.webservices.data.Recurrence;
import microsoft.exchange.webservices.data.SearchFilter;
import microsoft.exchange.webservices.data.SearchFilter.SearchFilterCollection;
import microsoft.exchange.webservices.data.ServiceLocalException;
import microsoft.exchange.webservices.data.StreamingSubscription;
import microsoft.exchange.webservices.data.StreamingSubscriptionConnection;
import microsoft.exchange.webservices.data.StreamingSubscriptionConnection.INotificationEventDelegate;
import microsoft.exchange.webservices.data.StreamingSubscriptionConnection.ISubscriptionErrorDelegate;
import microsoft.exchange.webservices.data.SubscriptionErrorEventArgs;
import microsoft.exchange.webservices.data.WebCredentials;
import microsoft.exchange.webservices.data.WellKnownFolderName;

import org.joda.time.DateTime;

import com.zerodes.exchangesync.TaskObserver;
import com.zerodes.exchangesync.calendarsource.CalendarSource;
import com.zerodes.exchangesync.dto.AppointmentDto;
import com.zerodes.exchangesync.dto.AppointmentDto.RecurrenceType;
import com.zerodes.exchangesync.dto.PersonDto;
import com.zerodes.exchangesync.dto.TaskDto;
import com.zerodes.exchangesync.tasksource.TaskSource;

public class ExchangeSourceImpl implements TaskSource, CalendarSource {
	private static final int MAX_RESULTS = 1000;
	private static final int SUBSCRIPTION_TIMEOUT = 30; // in minutes
	private static final boolean ENABLE_DEBUGGING = false;

	private static final UUID PROPERTY_SET_TASK = UUID.fromString("00062003-0000-0000-C000-000000000046");
	private static final UUID PROPERTY_SET_COMMON = UUID.fromString("00062008-0000-0000-C000-000000000046");

	private static final int PID_TAG_FLAG_STATUS = 0x1090; // http://msdn.microsoft.com/en-us/library/cc842307
	private static final int PID_LID_FLAG_REQUEST = 0x8018; // http://msdn.microsoft.com/en-us/library/cc815496
	private static final int PID_LID_TODO_ORDINAL_DATE = 0x8021; // http://msdn.microsoft.com/en-us/library/cc842320
	private static final int PID_LID_TODO_SUB_ORDINAL = 0x8022; // http://msdn.microsoft.com/en-us/library/cc839908
	private static final int PID_LID_TASK_COMPLETE = 0x8023; // http://msdn.microsoft.com/en-us/library/cc839514
	private static final int PID_LID_TASK_STATUS = 0x8024; // http://msdn.microsoft.com/en-us/library/cc842120
	private static final int PID_LID_TODO_TITLE = 0x8025; // http://msdn.microsoft.com/en-us/library/cc842303
	private static final int PID_LID_TASK_START_DATE = 0x802A; // http://msdn.microsoft.com/en-us/library/cc815922
	private static final int PID_LID_TASK_DUE_DATE = 0x8105; // http://msdn.microsoft.com/en-us/library/cc839641
	private static final int PID_LID_TASK_DATE_COMPLETED = 0x810F; // http://msdn.microsoft.com/en-us/library/cc815753
	private static final int PID_LID_PERCENT_COMPLETE = 0x802F; // http://msdn.microsoft.com/en-us/library/cc839932
	private static final int PID_LID_TASK_MODE = 0x8161; // http://msdn.microsoft.com/en-us/library/cc765719

	private static final ExtendedPropertyDefinition PR_FLAG_STATUS = new ExtendedPropertyDefinition(PID_TAG_FLAG_STATUS, MapiPropertyType.Integer);
	private static final ExtendedPropertyDefinition PR_FLAG_REQUEST = new ExtendedPropertyDefinition(PROPERTY_SET_COMMON, PID_LID_FLAG_REQUEST, MapiPropertyType.String);
	private static final ExtendedPropertyDefinition PR_TODO_ORDINAL_DATE = new ExtendedPropertyDefinition(PROPERTY_SET_COMMON, PID_LID_TODO_ORDINAL_DATE, MapiPropertyType.SystemTime);
	private static final ExtendedPropertyDefinition PR_TODO_SUB_ORDINAL = new ExtendedPropertyDefinition(PROPERTY_SET_COMMON, PID_LID_TODO_SUB_ORDINAL, MapiPropertyType.String);
	private static final ExtendedPropertyDefinition PR_TASK_COMPLETE = new ExtendedPropertyDefinition(PROPERTY_SET_COMMON, PID_LID_TASK_COMPLETE, MapiPropertyType.Boolean);
	private static final ExtendedPropertyDefinition PR_TASK_STATUS = new ExtendedPropertyDefinition(PROPERTY_SET_COMMON, PID_LID_TASK_STATUS, MapiPropertyType.Integer);
	private static final ExtendedPropertyDefinition PR_TODO_TITLE = new ExtendedPropertyDefinition(PROPERTY_SET_COMMON, PID_LID_TODO_TITLE, MapiPropertyType.String);
	private static final ExtendedPropertyDefinition PR_TASK_START_DATE = new ExtendedPropertyDefinition(PROPERTY_SET_COMMON, PID_LID_TASK_START_DATE, MapiPropertyType.SystemTime);
	private static final ExtendedPropertyDefinition PR_TASK_DUE_DATE = new ExtendedPropertyDefinition(PROPERTY_SET_TASK, PID_LID_TASK_DUE_DATE, MapiPropertyType.SystemTime);
	private static final ExtendedPropertyDefinition PR_TASK_DATE_COMPLETED = new ExtendedPropertyDefinition(PROPERTY_SET_COMMON, PID_LID_TASK_DATE_COMPLETED, MapiPropertyType.SystemTime);
	private static final ExtendedPropertyDefinition PR_PERCENT_COMPLETE = new ExtendedPropertyDefinition(PROPERTY_SET_COMMON, PID_LID_PERCENT_COMPLETE, MapiPropertyType.Double);
	private static final ExtendedPropertyDefinition PR_TASK_MODE = new ExtendedPropertyDefinition(PROPERTY_SET_COMMON, PID_LID_TASK_MODE, MapiPropertyType.Integer);
	private static final ExtendedPropertyDefinition PR_ALL_FOLDERS = new ExtendedPropertyDefinition(13825, MapiPropertyType.Integer);

	private static final int PR_FLAG_STATUS_FOLLOWUP_COMPLETE = 1;
	private static final int PR_FLAG_STATUS_FOLLOWUP_FLAGGED = 2;

	private final ExchangeService service;
	private final ExchangeEventsHandler eventsHandler;

	public ExchangeSourceImpl(final String mailHost, final String username, final String password) throws Exception {
		System.out.println("Connecting to Exchange (" + mailHost + ") as " + username + "...");

		final ExchangeCredentials credentials = new WebCredentials(username, password);
		service = new ExchangeService();
		service.setCredentials(credentials);
		try {
			service.setUrl(new URI("https://" + mailHost + "/EWS/Exchange.asmx"));
		} catch (final URISyntaxException e) {
			e.printStackTrace();
		}
		service.setTraceEnabled(ENABLE_DEBUGGING);

		// Setup a streaming subscription
		// http://blogs.msdn.com/b/exchangedev/archive/2010/12/22/working-with-streaming-notifications-by-using-the-ews-managed-api.aspx
		final StreamingSubscription streamingsubscription = service.subscribeToStreamingNotificationsOnAllFolders(EventType.Modified);
		final StreamingSubscriptionConnection connection = new StreamingSubscriptionConnection(service, SUBSCRIPTION_TIMEOUT);
		connection.addSubscription(streamingsubscription);
		eventsHandler = new ExchangeEventsHandler();
		connection.addOnDisconnect(eventsHandler);
		connection.addOnNotificationEvent(eventsHandler);
		connection.addOnSubscriptionError(eventsHandler);
		connection.open();

		System.out.println("Connected to Exchange");
	}

	private class ExchangeEventsHandler implements ISubscriptionErrorDelegate, INotificationEventDelegate {

		private final Set<TaskObserver> taskEventObservers = new HashSet<TaskObserver>();

		@Override
		public void subscriptionErrorDelegate(final Object sender, final SubscriptionErrorEventArgs args) {
			final StreamingSubscriptionConnection connection = (StreamingSubscriptionConnection) sender;
			if (args.getException() != null) {
				args.getException().printStackTrace();
			} else {
				try {
					System.out.println("Reconnected to Exchange streaming service.");
					connection.open();
				} catch (final ServiceLocalException e) {
					e.printStackTrace();
				} catch (final Exception e) {
					e.printStackTrace();
				}
			}
		}

		@Override
		public void notificationEventDelegate(final Object sender, final NotificationEventArgs args) {
			try {
				for (final NotificationEvent event : args.getEvents()) {
					if (event instanceof ItemEvent) {
						final ItemEvent itemEvent = (ItemEvent) event;
						final Item email = Item.bind(service, itemEvent.getItemId(), createEmailPropertySet());
						if (email != null) {
							notifyTaskEventListeners(convertToTaskDto((EmailMessage) email));
						}
					}
				}
			} catch (final Exception e) {
				e.printStackTrace();
			}
		}

		public void addTaskEventListener(final TaskObserver observer) {
			taskEventObservers.add(observer);
		}

		public void notifyTaskEventListeners(final TaskDto task) {
			for (final TaskObserver observer : taskEventObservers) {
				observer.taskChanged(task);
			}
		}
	}

	@Override
	public void addTask(final TaskDto task) {
		throw new UnsupportedOperationException("Unable to add new tasks to Exchange");
	}

	@Override
	public Set<TaskDto> getAllTasks() {
		final Set<TaskDto> results = new HashSet<TaskDto>();
		try {
			// Take a look at http://blogs.planetsoftware.com.au/paul/archive/2010/05/20/exchange-web-services-ews-managed-api-ndash-part-2.aspx
			final SearchFilterCollection searchFilterCollection = new SearchFilterCollection(LogicalOperator.Or);
			searchFilterCollection.add(new SearchFilter.IsEqualTo(PR_FLAG_STATUS, "1"));
			searchFilterCollection.add(new SearchFilter.IsEqualTo(PR_FLAG_STATUS, "2"));
			final ItemView itemView = new ItemView(MAX_RESULTS);
			itemView.setPropertySet(createEmailPropertySet());
			final FindItemsResults<Item> items = getAllItemsFolder().findItems(searchFilterCollection, itemView);
			for (final Item email : items.getItems()) {
				if (email instanceof EmailMessage) {
					results.add(convertToTaskDto((EmailMessage) email));
				}
			}
		} catch (final URISyntaxException e) {
			e.printStackTrace();
		} catch (final Exception e) {
			e.printStackTrace();
		}
		return results;
	}

	private Folder getAllItemsFolder() throws Exception {
		final FolderId rootFolderId = new FolderId(WellKnownFolderName.Root);
		final FolderView folderView = new FolderView(MAX_RESULTS);
		folderView.setTraversal(FolderTraversal.Shallow);

		final SearchFilter searchFilter1 = new SearchFilter.IsEqualTo(PR_ALL_FOLDERS, "2");
		final SearchFilter searchFilter2 = new SearchFilter.IsEqualTo(FolderSchema.DisplayName, "allitems");
		final SearchFilter.SearchFilterCollection searchFilterCollection = new SearchFilter.SearchFilterCollection(LogicalOperator.And);
		searchFilterCollection.add(searchFilter1);
		searchFilterCollection.add(searchFilter2);

		final FindFoldersResults findFoldersResults = service.findFolders(
				rootFolderId, searchFilterCollection, folderView);

		if (findFoldersResults.getFolders().size() == 0) {
			return null;
		}
		return findFoldersResults.getFolders().iterator().next();
	}

	public TaskDto convertToTaskDto(final EmailMessage email) throws ServiceLocalException {
		Integer flagValue = null;
		Date dueDate = null;
		for (final ExtendedProperty extendedProperty : email.getExtendedProperties()) {
			if (extendedProperty.getPropertyDefinition().getTag() != null && extendedProperty.getPropertyDefinition().getTag() == 16) {
				flagValue = (Integer) extendedProperty.getValue();
			} else if (extendedProperty.getPropertyDefinition().getId() != null && extendedProperty.getPropertyDefinition().getId() == PID_LID_TASK_DUE_DATE) {
				dueDate = (Date) extendedProperty.getValue();
			}
		}
		final TaskDto task = new TaskDto();
		task.setExchangeId(email.getId().getUniqueId());
		task.setLastModified(getCorrectedExchangeTime(email.getLastModifiedTime()));
		task.setName(email.getSubject());
		if (flagValue == null) {
			throw new RuntimeException("Found email without follow-up flag!");
		} else if (flagValue == PR_FLAG_STATUS_FOLLOWUP_COMPLETE) {
			task.setCompleted(true);
		}
		task.setDueDate(dueDate);
		return task;
	}
	
	public PersonDto convertToPersonDto(final EmailAddress email, boolean optional) {
		final PersonDto person = new PersonDto();
		person.setName(email.getName());
		if (email.getRoutingType().equals("SMTP")) {
			person.setEmail(email.getAddress());
		}
		return person;
	}

	public AppointmentDto convertToAppointmentDto(final Appointment appointment) throws ServiceLocalException {
		final AppointmentDto appointmentDto = new AppointmentDto();
		appointmentDto.setExchangeId(appointment.getId().getUniqueId());
		appointmentDto.setLastModified(getCorrectedExchangeTime(appointment.getLastModifiedTime()));
		appointmentDto.setSummary(appointment.getSubject());
		try {
			appointmentDto.setDescription(MessageBody.getStringFromMessageBody(appointment.getBody()));
		} catch (final Exception e) {
			e.printStackTrace();
		}
		appointmentDto.setStart(getCorrectedExchangeTime(appointment.getStart()));
		appointmentDto.setEnd(getCorrectedExchangeTime(appointment.getEnd()));
		appointmentDto.setLocation(appointment.getLocation());
		if (appointment.getOrganizer() != null) {
			appointmentDto.setOrganizer(convertToPersonDto(appointment.getOrganizer(), false));
		}
		final Set<PersonDto> attendees = new HashSet<PersonDto>();
		if (appointment.getRequiredAttendees() != null) {
			for (final Attendee exchangeAttendee : appointment.getRequiredAttendees()) {
				attendees.add(convertToPersonDto(exchangeAttendee, false));
			}
		}
		if (appointment.getOptionalAttendees() != null) {
			for (final Attendee exchangeAttendee : appointment.getOptionalAttendees()) {
				attendees.add(convertToPersonDto(exchangeAttendee, true));
			}
		}
		appointmentDto.setAttendees(attendees);
		appointmentDto.setReminderMinutesBeforeStart(appointment.getReminderMinutesBeforeStart());
		if (appointment.getRecurrence() != null) {
			if (appointment.getRecurrence() instanceof Recurrence.DailyPattern) {
				appointmentDto.setRecurrenceType(RecurrenceType.DAILY);
			} else if (appointment.getRecurrence() instanceof Recurrence.WeeklyPattern) {
				appointmentDto.setRecurrenceType(RecurrenceType.WEEKLY);
			} else if (appointment.getRecurrence() instanceof Recurrence.MonthlyPattern) {
				appointmentDto.setRecurrenceType(RecurrenceType.MONTHLY);
			} else if (appointment.getRecurrence() instanceof Recurrence.YearlyPattern) {
				appointmentDto.setRecurrenceType(RecurrenceType.YEARLY);
			}
			appointmentDto.setRecurrenceCount(appointment.getRecurrence().getNumberOfOccurrences());
		}
		return appointmentDto;
	}

	public AppointmentDto convertToAppointmentDto(final MeetingRequest meeting) throws ServiceLocalException {
		final AppointmentDto appointmentDto = new AppointmentDto();
		appointmentDto.setExchangeId(meeting.getId().getUniqueId());
		appointmentDto.setLastModified(getCorrectedExchangeTime(meeting.getLastModifiedTime()));
		appointmentDto.setSummary(meeting.getSubject());
		try {
			appointmentDto.setDescription(MessageBody.getStringFromMessageBody(meeting.getBody()));
		} catch (final Exception e) {
			e.printStackTrace();
		}
		appointmentDto.setStart(getCorrectedExchangeTime(meeting.getStart()));
		appointmentDto.setEnd(getCorrectedExchangeTime(meeting.getEnd()));
		appointmentDto.setLocation(meeting.getLocation());
		if (meeting.getOrganizer() != null) {
			appointmentDto.setOrganizer(convertToPersonDto(meeting.getOrganizer(), false));
		}
		final Set<PersonDto> attendees = new HashSet<PersonDto>();
		if (meeting.getRequiredAttendees() != null) {
			for (final Attendee exchangeAttendee : meeting.getRequiredAttendees()) {
				attendees.add(convertToPersonDto(exchangeAttendee, false));
			}
		}
		if (meeting.getOptionalAttendees() != null) {
			for (final Attendee exchangeAttendee : meeting.getOptionalAttendees()) {
				attendees.add(convertToPersonDto(exchangeAttendee, true));
			}
		}
		appointmentDto.setAttendees(attendees);
		appointmentDto.setReminderMinutesBeforeStart(meeting.getReminderMinutesBeforeStart());
		if (meeting.getRecurrence() != null) {
			if (meeting.getRecurrence() instanceof Recurrence.DailyPattern) {
				appointmentDto.setRecurrenceType(RecurrenceType.DAILY);
			} else if (meeting.getRecurrence() instanceof Recurrence.WeeklyPattern) {
				appointmentDto.setRecurrenceType(RecurrenceType.WEEKLY);
			} else if (meeting.getRecurrence() instanceof Recurrence.MonthlyPattern) {
				appointmentDto.setRecurrenceType(RecurrenceType.MONTHLY);
			} else if (meeting.getRecurrence() instanceof Recurrence.YearlyPattern) {
				appointmentDto.setRecurrenceType(RecurrenceType.YEARLY);
			}
			appointmentDto.setRecurrenceCount(meeting.getRecurrence().getNumberOfOccurrences());
		}
		return appointmentDto;
	}

	/**
	 * There is a bug in the Java EWS in which time is returned in GMT but with local timezone.
	 * This function fixes those times.
	 *
	 * @param theDate the date returned from EWS
	 * @return theDate converted to local time
	 */
	private Date getCorrectedExchangeTime(final Date theDate) {
		final TimeZone tz = Calendar.getInstance().getTimeZone();

		final long msFromEpochGmt = theDate.getTime();

		// gives you the current offset in ms from GMT at the current date
		final int offsetFromUTC = tz.getOffset(msFromEpochGmt);

		// create a new calendar in GMT timezone, set to this date and add the
		// offset
		final Calendar newTime = Calendar.getInstance();
		newTime.setTime(theDate);
		newTime.add(Calendar.MILLISECOND, offsetFromUTC);
		return newTime.getTime();
	}

	private PropertySet createIdOnlyPropertySet() {
		final PropertySet propertySet = new PropertySet(BasePropertySet.IdOnly);
		return propertySet;
	}

	private PropertySet createEmailPropertySet() {
		final ExtendedPropertyDefinition[] extendedPropertyDefinitions = new ExtendedPropertyDefinition[] { PR_FLAG_STATUS, PR_TASK_DUE_DATE };
		final PropertySet propertySet = new PropertySet(BasePropertySet.FirstClassProperties, extendedPropertyDefinitions);
		return propertySet;
	}

	private PropertySet createCalendarPropertySet() {
		final PropertySet propertySet = new PropertySet(BasePropertySet.FirstClassProperties);
		propertySet.setRequestedBodyType(BodyType.Text);
		return propertySet;
	}

	public void addTaskEventListener(final TaskObserver observer) {
		eventsHandler.addTaskEventListener(observer);
	}

	@Override
	public void updateDueDate(final TaskDto task) {
		try {
			final ItemId itemId = new ItemId(task.getExchangeId());
			final Item email = Item.bind(service, itemId, createEmailPropertySet());
			if (task.getDueDate() == null) {
				email.removeExtendedProperty(PR_TASK_DUE_DATE);
			} else {
				email.setExtendedProperty(PR_TASK_DUE_DATE, task.getDueDate());
			}
			email.update(ConflictResolutionMode.AlwaysOverwrite);
		} catch (final Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public void updateCompletedFlag(final TaskDto task) {
		try {
			final ItemId itemId = new ItemId(task.getExchangeId());
			final Item email = Item.bind(service, itemId, createEmailPropertySet());
			email.setExtendedProperty(PR_TODO_TITLE, task.getName());
			email.setExtendedProperty(PR_TASK_MODE, 0); // Task is not assigned
			if (task.isCompleted()) {
				email.removeExtendedProperty(PR_FLAG_REQUEST);
				email.setExtendedProperty(PR_TASK_COMPLETE, true);
				email.setExtendedProperty(PR_PERCENT_COMPLETE, 1d);
				email.setExtendedProperty(PR_TASK_DATE_COMPLETED, new Date());
				email.setExtendedProperty(PR_TASK_STATUS, 2); // User's work on this task is complete
				email.setExtendedProperty(PR_FLAG_STATUS, PR_FLAG_STATUS_FOLLOWUP_COMPLETE);
			} else {
				email.setExtendedProperty(PR_TASK_START_DATE, new Date());
				email.setExtendedProperty(PR_FLAG_REQUEST, "Follow up");
				email.setExtendedProperty(PR_TODO_ORDINAL_DATE, new Date());
				email.setExtendedProperty(PR_TODO_SUB_ORDINAL, "5555555");
				email.setExtendedProperty(PR_TASK_COMPLETE, false);
				email.setExtendedProperty(PR_PERCENT_COMPLETE, 0d);
				email.removeExtendedProperty(PR_TASK_DATE_COMPLETED);
				email.setExtendedProperty(PR_TASK_STATUS, 0);
				email.setExtendedProperty(PR_FLAG_STATUS, PR_FLAG_STATUS_FOLLOWUP_FLAGGED);
			}
			email.update(ConflictResolutionMode.AlwaysOverwrite);
		} catch (final Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public Collection<AppointmentDto> getAllAppointments() {
		final Set<AppointmentDto> results = new HashSet<AppointmentDto>();
		try {
			final DateTime now = new DateTime();
			final DateTime minus6Months = now.minusMonths(6);
			final DateTime plus6Months = now.plusMonths(6);
			final CalendarView calendarView = new CalendarView(minus6Months.toDate(), plus6Months.toDate(), MAX_RESULTS);
			calendarView.setPropertySet(createIdOnlyPropertySet());
			final FindItemsResults<Appointment> appointments = service.findAppointments(WellKnownFolderName.Calendar, calendarView);
			service.loadPropertiesForItems(appointments, createCalendarPropertySet());
			for (final Appointment appointment : appointments.getItems()) {
				results.add(convertToAppointmentDto(appointment));
			}
		} catch (final URISyntaxException e) {
			e.printStackTrace();
		} catch (final Exception e) {
			e.printStackTrace();
		}
		return results;
	}

	@Override
	public void addAppointment(final AppointmentDto task) {
		throw new UnsupportedOperationException("Unable to add new appointments to Exchange");
	}

	@Override
	public void updateAppointment(AppointmentDto appointment) {
		throw new UnsupportedOperationException("Unable to update appointments in Exchange");
	}

	@Override
	public void deleteAppointment(AppointmentDto appointment) {
		throw new UnsupportedOperationException("Unable to delete appointments in Exchange");
	}
}