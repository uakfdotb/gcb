package gcb;

import gcb.plugin.Plugin;
import gcb.plugin.PluginManager;
import gcb.MemberInfo;
import gcb.GCBConfig;
import java.net.*;
import java.io.*;
import java.util.*;
import gcb.plugindb;
import gcb.GChatBot;

public class trivia extends Plugin {
	PluginManager manager;
	
	String commandTrigger;
	
	boolean triviaEnabled;
	int questionDelay; //ms delay after a question is answered
	int triviaDelay; //ms delay in between hints
	String questionDifficulty; //null is difficulty off
	String questionCategory; //null is category off
	
	// depending on state next operation should be:
	// 0: display the question
	// 1: display ???? (# characters in answer)
	// 2: display ?a?a (stage2 answer)
	// 3: display aa?a (stage3 answer)
	// 4: display aaaa (display answer) and -> 0
	int triviaState;
	
	ArrayList<String> questionAnswers; //acceptable answers to current question
	char[] questionUncover; //hint for current question
	
	ArrayList<StoredQuestion> triviaQuestions; //stored questions, with question/answer
	long lastTime; //last time an event occurred; used for update loop
	
	boolean terminate = false;
	
	plugindb pdb;

	public void init(PluginManager manager) {
		this.manager = manager;
		
		commandTrigger = GCBConfig.configuration.getString("gcb.trivia_trigger", "trivia");
		
		triviaEnabled = false;
		questionDelay = GCBConfig.configuration.getInt("gcb.trivia_questiondelay", 6000);
		triviaDelay = GCBConfig.configuration.getInt("gcb.trivia_delay", 8000);
		questionDifficulty = null;
		questionCategory = null;
		
		triviaState = 0;
		
		questionAnswers = new ArrayList<String>();
		triviaQuestions = new ArrayList<StoredQuestion>();
		
		//initialize plugindb
		pdb = new plugindb();
		pdb.init(manager);
		pdb.setPluginName("trivia");
		pdb.dbconnect();
		pdb.dbGetScores();
		manager.log("[TRIVIA] Loaded " + pdb.dbScoreNum() + " scores!");
	}
	
	public void load() {
		manager.register(this, "onCommand");
		manager.getGarena().registerListener(this);
		manager.registerDelayed(this, "updateloop", 0);
	}
	
	public void unload() {
		manager.deregister(this, "onCommand");
		manager.getGarena().deregisterListener(this);
		terminate = true;
	}
	
	public void chatReceived(MemberInfo player, String text, boolean whisper) {
		if(triviaEnabled && triviaState > 0) {
			String userAnswer = text.toLowerCase();
		
			if(questionAnswers.contains(userAnswer)) {
				pdb.dbScoreAdd(player.username.toLowerCase(), 1);
		
				//get new score
				int newScore = pdb.dbGetScore(player.username.toLowerCase());
			
				manager.say("The answer was: " + userAnswer + "; user " + player.username + " got it correct! (points: " + newScore + ")");
				//reset
				lastTime = System.currentTimeMillis();
				triviaState = 0;
			}
		}
	}

	public String onCommand(MemberInfo player, String command, String payload, int rank) {
		if(command.equals(commandTrigger)) {
			String[] parts = payload.split(" ");
			
			if(rank >= GChatBot.LEVEL_ADMIN) {
				if(parts[0].equalsIgnoreCase("on")) {
					triviaEnabled = true;
					triviaState = 0;
					lastTime = 0;
					
					manager.log("[TRIVIA] Enabled with category=" + questionCategory + " and diff=" + questionDifficulty);
					
					return "Enabled trivia!";
				} else if(parts[0].equalsIgnoreCase("off")) {
					triviaEnabled = false;
					return "Disabled trivia!";
				} else if(parts[0].equals("delay") && parts.length >= 2) {
					triviaDelay = Integer.parseInt(parts[1]);
					return "Set delay!";
				} else if(parts[0].equals("category")) {
					if(parts.length >= 2) {
						questionCategory = parts[1];
					} else {
						questionCategory = null;
					}
					
					//clear questions since we changed the type
					triviaQuestions.clear();
					return "Set category!";
				} else if(parts[0].equals("difficulty")) {
					if(parts.length >= 2) {
						questionDifficulty = parts[1];
					} else {
						questionDifficulty = null;
					}
					
					//clear questions since we changed the type
					triviaQuestions.clear();
					return "Set difficulty!";
				}
			}
			
			if(parts[0].equals("top")) {
				//display top five
				return pdb.dbScoreTopStr(5);
			} else if(parts[0].equalsIgnoreCase("score")) {
				String lowername = player.username.toLowerCase();
				
				if(parts.length >= 2) {
					lowername = parts[1].toLowerCase();
				}
				
				return lowername + " points: " + pdb.dbGetScore(lowername);
			}
		}
		
		return null;
	}
	
	public void onDelay(String arg) {
		if(arg.equals("updateloop")) {
			updateLoop();
		}
	}
	
	public void updateLoop() {
		while(!terminate) {
			if(triviaEnabled) {
				if(triviaState == 0) {
					if(System.currentTimeMillis() - lastTime > questionDelay) {
						askQuestion();
					
						//reset
						triviaState = 1;
						lastTime = System.currentTimeMillis();
					}
				}
				else if(triviaState == 1) {
					if(System.currentTimeMillis() - lastTime > triviaDelay) {
						manager.say("Hint: " + new String(questionUncover));
				
						//reset
						triviaState = 2;
						lastTime = System.currentTimeMillis();
					}
				}
				else if(triviaState == 2 || triviaState == 3) {
					if(System.currentTimeMillis() - lastTime > triviaDelay) {
						uncover();
						manager.say("Hint: " + new String(questionUncover));
					
						//reset
						triviaState++;
						lastTime = System.currentTimeMillis();
					}
				}
				else if(triviaState == 4) {
					if(System.currentTimeMillis() - lastTime > triviaDelay) {
						//no one answered; let's show them the answer
						manager.say("The answer was: " + questionAnswers.get(0));
						//reset some things
						triviaState = 0;
						lastTime = System.currentTimeMillis();
					}
				}
			}
		
			try {
				Thread.sleep(200);
			} catch(InterruptedException e) {}
		}
	}

	public void uncover() {
		//calculate how many to uncover; note that we might uncover same one twice (good thing: more randomness)
		//this formula ensures that we don't uncover too many
		int num_uncover = (int) Math.round(0.33 * (double) questionUncover.length);
	
		if(questionUncover.length < 2) {
			num_uncover = 0;
		} else if(questionUncover.length < 4) {
			num_uncover = 1;
		}
	
		for(int i = 0; i < num_uncover; i++) {
			int index = (int) (Math.random() * questionUncover.length);
			questionUncover[index] = questionAnswers.get(0).charAt(index);
		}
	}

	public void askQuestion() {
		//make sure there are questions available
		if(triviaQuestions.isEmpty()) {
			addQuestions();
		}
	
		StoredQuestion storedQuestion = triviaQuestions.remove(0);
		questionAnswers = storedQuestion.answers;
	
		//generate trivia_uncover
		String firstAnswer = questionAnswers.get(0);
		questionUncover = new char[firstAnswer.length()];
		for(int i = 0; i < questionUncover.length; i++) {
			if(firstAnswer.charAt(i) != ' ')
				questionUncover[i] = '?';
			else
				questionUncover[i] = firstAnswer.charAt(i);
		}
	
		manager.say(storedQuestion.question + " (category: " + storedQuestion.category + ")");
	}

	public void addQuestions() {
		String target = "http://snapnjacks.com/getq.php?client=plugins/pychop/trivia";
	
		String content = null;
		try {
			//quote custom parameters to replace with %XX
			if(questionDifficulty != null) {
				target += "&dif=" + URLEncoder.encode(questionDifficulty, "UTF-8");
			}
	
			if(questionCategory != null) {
				target += "&ctg=" + URLEncoder.encode(questionCategory, "UTF-8");
			}
	
			manager.log("[TRIVIA] Reading questions from " + target);
		
			URL targetURL = new URL(target);
			BufferedReader in = new BufferedReader(new InputStreamReader(targetURL.openStream()));
			content = in.readLine();
			in.close();
		}
		catch(IOException ioe) {
			manager.log("[TRIVIA] Error: unable to read " + target + ":" + ioe.getLocalizedMessage());
			
			StoredQuestion errorQuestion = new StoredQuestion();
			errorQuestion.question = "Unable to load questions! You won't be able to answer this question D:";
			errorQuestion.answers.add("error");
			triviaQuestions.add(errorQuestion);
		
			//disable trivia
			triviaEnabled = false;
		
			return;
		}
	
		String[] questionSplit = content.split("\\*\\*");
	
		for(String questionString : questionSplit) {
			StoredQuestion question = new StoredQuestion();
			String[] parts = questionString.split("\\|");
		
			if(parts.length < 7) {
				continue;
			}
		
			String[] unformattedAnswers = parts[1].split("\\/");
		
			for(String x : unformattedAnswers) {
				//remove whitespace and convert to lowercase
				question.answers.add(x.toLowerCase().trim());
			}
		
			//parts[0] is question, parts[6] is category
			question.question = parts[0];
			question.category = parts[6];
			triviaQuestions.add(question);
			manager.log("[TRIVIA] Appended question: " + question.question + "; storing " + triviaQuestions.size() + " questions now");
		}
	}
}

class StoredQuestion {
	String question;
	ArrayList<String> answers;
	String category;
	
	public StoredQuestion() {
		question = "";
		answers = new ArrayList<String>();
		category = "";
	}
}