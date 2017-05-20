package org.cities.predictor;
import org.json.JSONArray;
import org.json.JSONObject;
import weka.classifiers.bayes.NaiveBayes;
import weka.core.Instances;
import weka.core.Instance;
import weka.core.DenseInstance;
import weka.core.Utils;
import weka.core.converters.ConverterUtils.DataSource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.TimeUnit;

/**
 * Created by carlos on 16/05/17.
 */
public class Predictor {
    private static final String API_KEY = "6ab6acff-85f6-4580-b127-32067e5667ab";
    private static final String BASE_URL = "https://euw1.api.riotgames.com/api/lol/euw/";

    public static void main(String[] args) throws Exception {
        // load data
        Instances train = DataSource.read("data/clean_lk_0905_0-5.arff");
        train.setClassIndex(train.numAttributes() - 1);

        // Constructor creating an empty set of instances. Copies references to the header information from the given set of instances.
        Instances newData = null;
        newData = new Instances(train,1);

        long matchId = 3166095643L;
        Instance inst = extractDataFromMatchId(matchId, new JSONObject(), newData);

        System.out.println(inst.toString());

        /*


        // train classifier
        NaiveBayes cls = new NaiveBayes();
        cls.buildClassifier(train);

        // output predictions
        System.out.println("# - actual - predicted - error - distribution");

        double pred = cls.classifyInstance(inst);
        double[] dist = cls.distributionForInstance(inst);

        System.out.print(newData.classAttribute().value((int) pred));
        System.out.print(" - ");
        System.out.print(Utils.arrayToString(dist));
        System.out.println();
        */

    }

    public static Instance extractDataFromMatchId(long matchId, JSONObject champsWr, Instances dataSetInstances) {
        Instance inst = new DenseInstance(41);
        inst.setDataset(dataSetInstances);

        /* Output parameters */
        String winner = "";
        double[] champWr = new double[10];
        double[] playerChampWr = new double[10];
        double[] playerRecentWr = new double[10];
        int[] playerChampTimesPlayed = new int[10];
        int[] playerRanking = new int[10];

        JSONObject match = executeGetRequest("https://euw1.api.riotgames.com/api/lol/euw/v2.2/match/"+matchId+"?api_key="+API_KEY);
        if(match == null) {
            // devolverá null si hay un error o timeout en la conexión
            System.out.println("Get request null");
        }

        JSONArray teams = match.getJSONArray("teams");

        /* Team1Win */

        if(teams.getJSONObject(0).getBoolean("winner") == true) {
            winner = "Team1";
        } else {
            winner = "Team2";
        }

        /* playerChampWr */

        String participantSummonerIds = "";

        JSONArray participants = match.getJSONArray("participants");
        JSONArray participantsIdentities = match.getJSONArray("participantIdentities");
        for(int j = 0; j < participantsIdentities.length(); j++) {

            // me guio por el sumoner id y participant id de la identidad
            long summonerId = participantsIdentities.getJSONObject(j).getJSONObject("player").getLong("summonerId");
            int participantId = participantsIdentities.getJSONObject(j).getInt("participantId");

            // creo la lista de summoners ids para luego hacer la peticion de ligas
            if(j == participants.length() - 1)
                participantSummonerIds += summonerId;
            else
                participantSummonerIds += summonerId+",";

            // busco el participantid en la lista y saco el champion que juega
            int participantChampionId = 0;
            for(int j2 = 0; j2 < participants.length(); j2++) {
                if(participants.getJSONObject(j2).getInt("participantId") == participantId) {
                    participantChampionId = participants.getJSONObject(j2).getInt("championId");
                    break;
                }
            }
            // Extraigo de la lista de winrates de cahmpions estatica, el wr del  champion que esta jugando
            champWr[participantId-1] = champsWr.getDouble(Integer.toString(participantChampionId));

            // hago la peticion al endpoint de stats para ver el wr y partidas jugadas con ese champ
            JSONObject participant_stats_by_champ = executeGetRequest(BASE_URL+"v1.3/stats/by-summoner/"+summonerId+"/ranked?season=SEASON2017&api_key="+API_KEY);
            JSONArray particpant_stats_champ_list = participant_stats_by_champ.getJSONArray("champions");

            // busco el champion que esta jugando en esta partida dentro de la lista de todos lo que ha jugado
            for(int c = 0; c < particpant_stats_champ_list.length(); c++) {

                if(particpant_stats_champ_list.getJSONObject(c).getInt("id") == participantChampionId) {

                    JSONObject champ_stats = particpant_stats_champ_list.getJSONObject(c).getJSONObject("stats");

                    playerChampTimesPlayed[participantId-1] = champ_stats.getInt("totalSessionsPlayed");
                    playerChampWr[participantId-1] = (double)champ_stats.getInt("totalSessionsWon") / (double)champ_stats.getInt("totalSessionsPlayed");

                    break;
                }
            }
        }

        JSONObject participantsLeagueInfo = executeGetRequest(BASE_URL+"v2.5/league/by-summoner/"+participantSummonerIds+"/entry?api_key="+API_KEY);

        // Vuelvo a recorrer la lista de participantes para encontrar la parte que le corresponde en la respuesta conjunta de las ligas
        for(int p = 0; p < participantsIdentities.length(); p++) {
            long summonerId = participantsIdentities.getJSONObject(p).getJSONObject("player").getLong("summonerId");
            int participantId = participantsIdentities.getJSONObject(p).getInt("participantId");
            if(participantsLeagueInfo.has(Long.toString(summonerId))) {
                // Recorro las diferentes ligas que tiene el jguador
                JSONArray leagues = participantsLeagueInfo.getJSONArray(Long.toString(summonerId));
                for(int l = 0; l < leagues.length(); l++) {
                    if(leagues.getJSONObject(l).getString("queue").equals("RANKED_SOLO_5x5")) {
                        JSONObject pLeagueInfo = leagues.getJSONObject(l);
                        playerRanking[participantId-1] = getTierMapping(pLeagueInfo.getString("tier")) + getDivisionMapping(pLeagueInfo.getJSONArray("entries").getJSONObject(0).getString("division"));
                        break;
                    }
                }
            }
            else {
                System.err.println("League information not found for participant number "+participantId);
            }
        }

        System.out.println("Match procesado, creando instancia...");
        // Match procesado, procedemos a guardar
        try {
            for(int in = 0; in < champWr.length; in++) {
                inst.setValue(in, champWr[in]);
            }
            for(int in = 0; in < playerChampWr.length; in++) {
                inst.setValue(in+10, playerChampWr[in]);
            }
            for(int in = 0; in < playerChampTimesPlayed.length; in++) {
                inst.setValue(in+20, playerChampTimesPlayed[in]);
            }
            for(int in = 0; in < playerRanking.length; in++) {
                inst.setValue(in+30, playerRanking[in]);
            }
        } catch(Exception e) {
            System.err.println("Error writing match results "+ e.toString());
        }
        return inst;
    }

    public static int getDivisionMapping( String division ){
        switch( division ){
            case "I":
                return 5;
            case "II":
                return 4;
            case "III":
                return 3;
            case "IV":
                return 2;
            case "V":
                return 1;
            default:
                return 0;
        }
    }

    public static int getTierMapping( String tier ){
        switch( tier ){
            case "BRONZE":
                return 0;
            case "SILVER":
                return 6;
            case "GOLD":
                return 12;
            case "PLATINUM":
                return 18;
            case "DIAMOND":
                return 24;
            case "MASTER":
                return 30;
            case "CHALLENGER":
                return 36;
        }
        return 8;
    }

    public static JSONObject executeGetRequest(String url){
        try{
            /*
            requests += 1;
            if( requests % 500 == 0 ){
                TimeUnit.MINUTES.sleep(10);
            }*/
            TimeUnit.SECONDS.sleep(1);
            URL mh_url = new URL( url );
            System.out.println( url );
            HttpURLConnection con = (HttpURLConnection) mh_url.openConnection();
            con.setConnectTimeout(5000); // si tarda mas de 5 segundos lanza SocketTimeoutException
            int responseCode = con.getResponseCode();
            if( responseCode != 200 ){
                /*
                if(responseCode == 404) {
                    System.err.println("Response Code " + responseCode + " received.");
                    return null;
                }
                */

                System.err.println( "Response Code " + responseCode + " received. Re issuing request." );
                TimeUnit.SECONDS.sleep( 10 );
                return executeGetRequest( url);
            }
            BufferedReader in = new BufferedReader( new InputStreamReader( con.getInputStream() ) );
            String inputLine;
            StringBuffer response = new StringBuffer();

            while ((inputLine = in.readLine()) != null) {
                response.append(inputLine);
            }
            in.close();

            return new JSONObject( response.toString() );

        }catch( Exception e ){
            System.err.println( e );
            return null;
        }
    }
}
